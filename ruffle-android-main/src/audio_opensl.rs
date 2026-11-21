//! OpenSL ES audio backend.
//!
//! The `ndk` crate's audio bindings wrap AAudio, which only exists from Android 8.0
//! (API 26) onwards (`libaaudio.so` is missing before that). OpenSL ES, on the other
//! hand, has been part of the platform since API 9, so this backend keeps builds that
//! target Android 6-7 (like the SHIELD K1 on API 23) audible, matching the Flash
//! Player's audio behaviour instead of playing silently.
//!
//! The implementation mirrors what Oboe's OpenSL ES path does: a single output
//! player fed by an Android simple buffer queue, with the mixing done from the
//! queue's completion callback.

#![allow(dead_code)]

use std::ffi::c_void;
use std::ptr;

use ruffle_core::backend::audio::{
    swf, AudioBackend, AudioMixer, AudioMixerProxy, DecodeError, RegisterError, SoundHandle,
    SoundInstanceHandle, SoundStreamInfo, SoundTransform,
};

use ruffle_core::impl_audio_mixer_backend;

// --- Minimal OpenSL ES bindings --------------------------------------------------
// Only the pieces this backend touches are declared; layouts follow the NDK's
// <SLES/OpenSLES.h> and <SLES/OpenSLES_Android.h> exactly. Every interface is a
// pointer to a table of function pointers.

type SLresult = u32;
type SLboolean = u32;
type SLuint32 = u32;
type SLmillisecond = u32;

const SL_RESULT_SUCCESS: SLresult = 0;
const SL_BOOLEAN_FALSE: SLboolean = 0;
const SL_BOOLEAN_TRUE: SLboolean = 1;

const SL_PLAYSTATE_PAUSED: SLuint32 = 2;
const SL_PLAYSTATE_PLAYING: SLuint32 = 3;
const SL_PLAYSTATE_STOPPED: SLuint32 = 1;

const SL_DATAFORMAT_PCM: SLuint32 = 2;
const SL_SAMPLINGRATE_44_1: SLuint32 = 44_100_000;
const SL_BYTEORDER_LITTLEENDIAN: SLuint32 = 2;
const SL_SPEAKER_FRONT_LEFT: SLuint32 = 1;
const SL_SPEAKER_FRONT_RIGHT: SLuint32 = 2;
const SL_DATALOCATOR_OUTPUTMIX: SLuint32 = 4;
const SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE: SLuint32 = 0x8000_07BD;

#[repr(C)]
pub struct SLInterfaceID_ {
    time_low: SLuint32,
    time_mid: u16,
    time_hi_and_version: u16,
    clock_seq: u16,
    node: [u8; 6],
}
type SLInterfaceID = *const SLInterfaceID_;

type SLObjectItf = *const *const SLObjectItf_;

#[repr(C)]
struct SLObjectItf_ {
    realize: unsafe extern "C" fn(SLObjectItf, SLboolean) -> SLresult,
    resume: unsafe extern "C" fn(SLObjectItf, SLboolean) -> SLresult,
    get_state: unsafe extern "C" fn(SLObjectItf, *mut SLuint32) -> SLresult,
    get_interface: unsafe extern "C" fn(SLObjectItf, SLInterfaceID, *mut c_void) -> SLresult,
    register_callback: unsafe extern "C" fn(SLObjectItf, *const c_void, *mut c_void) -> SLresult,
    abort_async_operation: unsafe extern "C" fn(SLObjectItf),
    destroy: unsafe extern "C" fn(SLObjectItf),
    set_priority: unsafe extern "C" fn(SLObjectItf, i32, SLboolean) -> SLresult,
    get_priority: unsafe extern "C" fn(SLObjectItf, *mut i32, *mut SLboolean) -> SLresult,
    set_loss_of_control_interfaces:
        unsafe extern "C" fn(SLObjectItf, i16, *mut SLInterfaceID, SLboolean) -> SLresult,
}

type SLEngineItf = *const *const SLEngineItf_;

#[repr(C)]
struct SLEngineItf_ {
    create_led_device:
        unsafe extern "C" fn(SLEngineItf, *mut SLObjectItf, SLuint32, SLuint32, *const SLInterfaceID, *const SLboolean) -> SLresult,
    create_vibra_device:
        unsafe extern "C" fn(SLEngineItf, *mut SLObjectItf, SLuint32, SLuint32, *const SLInterfaceID, *const SLboolean) -> SLresult,
    create_audio_player:
        unsafe extern "C" fn(SLEngineItf, *mut SLObjectItf, *const SLDataSource, *const SLDataSink, SLuint32, *const SLInterfaceID, *const SLboolean) -> SLresult,
    create_audio_recorder:
        unsafe extern "C" fn(SLEngineItf, *mut SLObjectItf, *const SLDataSource, *const SLDataSink, SLuint32, *const SLInterfaceID, *const SLboolean) -> SLresult,
    create_midi_player:
        unsafe extern "C" fn(SLEngineItf, *mut SLObjectItf, *const SLDataSource, *const SLDataSource, *const SLDataSink, *const SLDataSink, *const SLDataSink, SLuint32, *const SLInterfaceID, *const SLboolean) -> SLresult,
    create_listener:
        unsafe extern "C" fn(SLEngineItf, *mut SLObjectItf, SLuint32, *const SLInterfaceID, *const SLboolean) -> SLresult,
    create3d_group:
        unsafe extern "C" fn(SLEngineItf, *mut SLObjectItf, SLuint32, *const SLInterfaceID, *const SLboolean) -> SLresult,
    create_output_mix:
        unsafe extern "C" fn(SLEngineItf, *mut SLObjectItf, SLuint32, *const SLInterfaceID, *const SLboolean) -> SLresult,
}

type SLPlayItf = *const *const SLPlayItf_;

#[repr(C)]
struct SLPlayItf_ {
    set_play_state: unsafe extern "C" fn(SLPlayItf, SLuint32) -> SLresult,
    get_play_state: unsafe extern "C" fn(SLPlayItf, *mut SLuint32) -> SLresult,
    get_duration: unsafe extern "C" fn(SLPlayItf, *mut SLmillisecond) -> SLresult,
    get_position: unsafe extern "C" fn(SLPlayItf, *mut SLmillisecond) -> SLresult,
}

type SLAndroidSimpleBufferQueueItf = *const *const SLAndroidSimpleBufferQueueItf_;

#[repr(C)]
struct SLAndroidSimpleBufferQueueItf_ {
    enqueue: unsafe extern "C" fn(SLAndroidSimpleBufferQueueItf, *const c_void, SLuint32) -> SLresult,
    clear: unsafe extern "C" fn(SLAndroidSimpleBufferQueueItf) -> SLresult,
    get_state: unsafe extern "C" fn(SLAndroidSimpleBufferQueueItf, *mut SLAndroidSimpleBufferQueueState) -> SLresult,
    register_callback: unsafe extern "C" fn(
        SLAndroidSimpleBufferQueueItf,
        unsafe extern "C" fn(SLAndroidSimpleBufferQueueItf, *mut c_void),
        *mut c_void,
    ) -> SLresult,
}

#[repr(C)]
struct SLAndroidSimpleBufferQueueState {
    count: SLuint32,
    index: SLuint32,
}

#[repr(C)]
struct SLDataLocator_AndroidSimpleBufferQueue {
    locator_type: SLuint32,
    num_buffers: SLuint32,
}

#[repr(C)]
struct SLDataLocator_OutputMix {
    locator_type: SLuint32,
    output_mix: SLObjectItf,
}

#[repr(C)]
struct SLDataFormat_PCM {
    format_type: SLuint32,
    num_channels: SLuint32,
    samples_per_sec: SLuint32,
    bits_per_sample: SLuint32,
    container_size: SLuint32,
    channel_mask: SLuint32,
    endianness: SLuint32,
}

#[repr(C)]
struct SLDataSource {
    p_locator: *const c_void,
    p_format: *const c_void,
}

#[repr(C)]
struct SLDataSink {
    p_locator: *const c_void,
    p_format: *const c_void,
}

#[link(name = "OpenSLES")]
extern "C" {
    fn slCreateEngine(
        engine: *mut SLObjectItf,
        num_options: SLuint32,
        options: *const c_void,
        num_interfaces: SLuint32,
        interface_ids: *const SLInterfaceID,
        interface_required: *const SLboolean,
    ) -> SLresult;

    static SL_IID_ENGINE: SLInterfaceID;
    static SL_IID_PLAY: SLInterfaceID;
    static SL_IID_ANDROIDSIMPLEBUFFERQUEUE: SLInterfaceID;
}

// --- Backend ---------------------------------------------------------------------

const SAMPLE_RATE: u32 = 44100;
const NUM_CHANNELS: usize = 2;
const BUFFER_FRAMES: usize = 1024;
const BUFFER_COUNT: usize = 4;
/// How many buffers are filled and queued before playback starts. The callback
/// refills one buffer per completion, so keeping two queued avoids underruns.
const SEED_BUFFERS: usize = 2;

/// Data shared with the OpenSL ES buffer queue callback.
struct CallbackState {
    proxy: AudioMixerProxy,
    queue: SLAndroidSimpleBufferQueueItf,
    buffers: Vec<Vec<i16>>,
    next: usize,
}

unsafe extern "C" fn buffer_queue_callback(
    _queue: SLAndroidSimpleBufferQueueItf,
    context: *mut c_void,
) {
    let state = &mut *(context as *mut CallbackState);
    let index = state.next;
    let buffer = &mut state.buffers[index];
    state.proxy.mix(buffer);
    let data = buffer.as_ptr() as *const c_void;
    let size = (buffer.len() * std::mem::size_of::<i16>()) as SLuint32;
    state.next = (index + 1) % state.buffers.len();

    let enqueue = (*(*state.queue)).enqueue;
    let _ = enqueue(state.queue, data, size);
}

unsafe fn object_realize(object: SLObjectItf) -> SLresult {
    ((*(*object)).realize)(object, SL_BOOLEAN_FALSE)
}

unsafe fn object_get_interface(
    object: SLObjectItf,
    interface_id: SLInterfaceID,
    out: *mut c_void,
) -> SLresult {
    ((*(*object)).get_interface)(object, interface_id, out)
}

unsafe fn object_destroy(object: SLObjectItf) {
    ((*(*object)).destroy)(object)
}

unsafe fn set_play_state(play: SLPlayItf, state: SLuint32) -> SLresult {
    ((*(*play)).set_play_state)(play, state)
}

fn failed(what: &str, result: SLresult) -> Box<dyn std::error::Error> {
    format!("{} failed with OpenSL ES result 0x{:08X}", what, result).into()
}

pub struct AudioBackendImpl {
    engine: SLObjectItf,
    output_mix: SLObjectItf,
    player: SLObjectItf,
    play: SLPlayItf,
    state: *mut CallbackState,
    mixer: AudioMixer,
    started: bool,
}

impl AudioBackendImpl {
    pub fn new() -> Result<Self, Box<dyn std::error::Error>> {
        let mixer = AudioMixer::new(NUM_CHANNELS as u8, SAMPLE_RATE);
        let proxy = mixer.proxy();

        unsafe {
            let mut engine: SLObjectItf = ptr::null();
            let engine_required = SL_BOOLEAN_TRUE;
            let result = slCreateEngine(
                &mut engine,
                0,
                ptr::null(),
                1,
                &SL_IID_ENGINE,
                &engine_required,
            );
            if result != SL_RESULT_SUCCESS {
                return Err(failed("slCreateEngine", result));
            }
            let result = object_realize(engine);
            if result != SL_RESULT_SUCCESS {
                object_destroy(engine);
                return Err(failed("engine realize", result));
            }

            let mut engine_itf: SLEngineItf = ptr::null();
            let result = object_get_interface(
                engine,
                SL_IID_ENGINE,
                &mut engine_itf as *mut _ as *mut c_void,
            );
            if result != SL_RESULT_SUCCESS || engine_itf.is_null() {
                object_destroy(engine);
                return Err(failed("engine interface", result));
            }

            let mut output_mix: SLObjectItf = ptr::null();
            let result = ((*(*engine_itf)).create_output_mix)(
                engine_itf,
                &mut output_mix,
                0,
                ptr::null(),
                ptr::null(),
            );
            if result != SL_RESULT_SUCCESS {
                object_destroy(engine);
                return Err(failed("output mix", result));
            }
            let result = object_realize(output_mix);
            if result != SL_RESULT_SUCCESS {
                object_destroy(output_mix);
                object_destroy(engine);
                return Err(failed("output mix realize", result));
            }

            let locator = SLDataLocator_AndroidSimpleBufferQueue {
                locator_type: SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE,
                num_buffers: BUFFER_COUNT as SLuint32,
            };
            let format = SLDataFormat_PCM {
                format_type: SL_DATAFORMAT_PCM,
                num_channels: NUM_CHANNELS as SLuint32,
                samples_per_sec: SL_SAMPLINGRATE_44_1,
                bits_per_sample: 16,
                container_size: 16,
                channel_mask: SL_SPEAKER_FRONT_LEFT | SL_SPEAKER_FRONT_RIGHT,
                endianness: SL_BYTEORDER_LITTLEENDIAN,
            };
            let source = SLDataSource {
                p_locator: &locator as *const _ as *const c_void,
                p_format: &format as *const _ as *const c_void,
            };
            let sink_locator = SLDataLocator_OutputMix {
                locator_type: SL_DATALOCATOR_OUTPUTMIX,
                output_mix,
            };
            let sink = SLDataSink {
                p_locator: &sink_locator as *const _ as *const c_void,
                p_format: ptr::null(),
            };

            let interface_ids = [SL_IID_ANDROIDSIMPLEBUFFERQUEUE];
            let interface_required = [SL_BOOLEAN_TRUE];
            let mut player: SLObjectItf = ptr::null();
            let result = ((*(*engine_itf)).create_audio_player)(
                engine_itf,
                &mut player,
                &source,
                &sink,
                interface_ids.len() as SLuint32,
                interface_ids.as_ptr(),
                interface_required.as_ptr(),
            );
            if result != SL_RESULT_SUCCESS {
                object_destroy(output_mix);
                object_destroy(engine);
                return Err(failed("audio player", result));
            }
            let result = object_realize(player);
            if result != SL_RESULT_SUCCESS {
                object_destroy(player);
                object_destroy(output_mix);
                object_destroy(engine);
                return Err(failed("audio player realize", result));
            }

            let mut play: SLPlayItf = ptr::null();
            let result = object_get_interface(
                player,
                SL_IID_PLAY,
                &mut play as *mut _ as *mut c_void,
            );
            let mut queue: SLAndroidSimpleBufferQueueItf = ptr::null();
            let queue_result = object_get_interface(
                player,
                SL_IID_ANDROIDSIMPLEBUFFERQUEUE,
                &mut queue as *mut _ as *mut c_void,
            );
            if result != SL_RESULT_SUCCESS
                || queue_result != SL_RESULT_SUCCESS
                || play.is_null()
                || queue.is_null()
            {
                object_destroy(player);
                object_destroy(output_mix);
                object_destroy(engine);
                return Err(failed("player interfaces", result));
            }

            let buffers = (0..BUFFER_COUNT)
                .map(|_| vec![0i16; BUFFER_FRAMES * NUM_CHANNELS])
                .collect();
            let state = Box::into_raw(Box::new(CallbackState {
                proxy,
                queue,
                buffers,
                next: SEED_BUFFERS,
            }));

            let result = ((*(*queue)).register_callback)(
                queue,
                buffer_queue_callback,
                state as *mut c_void,
            );
            if result != SL_RESULT_SUCCESS {
                drop(Box::from_raw(state));
                object_destroy(player);
                object_destroy(output_mix);
                object_destroy(engine);
                return Err(failed("buffer queue callback", result));
            }

            Ok(Self {
                engine,
                output_mix,
                player,
                play,
                state,
                mixer,
                started: false,
            })
        }
    }

    pub fn recreate_stream_if_needed(&mut self) {}

    fn prime(&mut self) {
        let state = unsafe { &mut *self.state };
        for index in 0..SEED_BUFFERS {
            let buffer = &mut state.buffers[index];
            state.proxy.mix(buffer);
            let data = buffer.as_ptr() as *const c_void;
            let size = (buffer.len() * std::mem::size_of::<i16>()) as SLuint32;
            unsafe {
                ((*(*state.queue)).enqueue)(state.queue, data, size);
            }
        }
        state.next = SEED_BUFFERS;
    }
}

impl AudioBackend for AudioBackendImpl {
    impl_audio_mixer_backend!(mixer);

    fn play(&mut self) {
        if !self.started {
            self.prime();
            self.started = true;
        }
        unsafe {
            let _ = set_play_state(self.play, SL_PLAYSTATE_PLAYING);
        }
    }

    fn pause(&mut self) {
        unsafe {
            let _ = set_play_state(self.play, SL_PLAYSTATE_PAUSED);
        }
    }
}

impl Drop for AudioBackendImpl {
    fn drop(&mut self) {
        unsafe {
            if !self.play.is_null() {
                let _ = set_play_state(self.play, SL_PLAYSTATE_PAUSED);
            }
            if !self.player.is_null() {
                object_destroy(self.player);
            }
            if !self.output_mix.is_null() {
                object_destroy(self.output_mix);
            }
            if !self.engine.is_null() {
                object_destroy(self.engine);
            }
            if !self.state.is_null() {
                drop(Box::from_raw(self.state));
                self.state = ptr::null_mut();
            }
        }
    }
}
