use std::collections::VecDeque;
use std::ffi::c_void;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

use log::{error, info};

#[repr(C)]
struct AAudioStreamBuilder {
    _private: [u8; 0],
}

#[repr(C)]
struct AAudioStream {
    _private: [u8; 0],
}

const AAUDIO_OK: i32 = 0;
const AAUDIO_DIRECTION_OUTPUT: i32 = 0;
const AAUDIO_FORMAT_PCM_I16: i32 = 1;
const AAUDIO_FORMAT_PCM_FLOAT: i32 = 2;
const AAUDIO_PERFORMANCE_MODE_LOW_LATENCY: i32 = 12;
// Fallback performance mode if the device audio HAL rejects LOW_LATENCY.
const AAUDIO_PERFORMANCE_MODE_UNSPECIFIED: i32 = 0;
const AAUDIO_CALLBACK_RESULT_CONTINUE: i32 = 0;

// Ring buffer sized to 200 ms (sample_rate / 5) to absorb PRoot/Unix-socket jitter
// without introducing noticeable A/V lip-sync delay.
const RING_BUFFER_DIVISOR_200MS: usize = 5;
const MIN_RING_SAMPLE_RATE: usize = 8_000;
const PCM_I16_BYTES: usize = 2;
const PCM_FLOAT_BYTES: usize = 4;

type DataCallbackFn = extern "C" fn(
    stream: *mut AAudioStream,
    user_data: *mut c_void,
    audio_data: *mut c_void,
    num_frames: i32,
) -> i32;

extern "C" {
    fn AAudio_createStreamBuilder(builder: *mut *mut AAudioStreamBuilder) -> i32;
    fn AAudioStreamBuilder_delete(builder: *mut AAudioStreamBuilder) -> i32;
    fn AAudioStreamBuilder_setDirection(builder: *mut AAudioStreamBuilder, direction: i32);
    fn AAudioStreamBuilder_setSampleRate(builder: *mut AAudioStreamBuilder, sample_rate: i32);
    fn AAudioStreamBuilder_setChannelCount(builder: *mut AAudioStreamBuilder, channel_count: i32);
    fn AAudioStreamBuilder_setFormat(builder: *mut AAudioStreamBuilder, format: i32);
    fn AAudioStreamBuilder_setPerformanceMode(builder: *mut AAudioStreamBuilder, mode: i32);
    fn AAudioStreamBuilder_setDataCallback(
        builder: *mut AAudioStreamBuilder,
        callback: DataCallbackFn,
        user_data: *mut c_void,
    );
    fn AAudioStreamBuilder_openStream(
        builder: *mut AAudioStreamBuilder,
        stream: *mut *mut AAudioStream,
    ) -> i32;
    fn AAudioStream_requestStart(stream: *mut AAudioStream) -> i32;
    fn AAudioStream_requestStop(stream: *mut AAudioStream) -> i32;
    fn AAudioStream_close(stream: *mut AAudioStream) -> i32;
    fn AAudioStream_getSampleRate(stream: *mut AAudioStream) -> i32;
    fn AAudioStream_getChannelCount(stream: *mut AAudioStream) -> i32;
}

pub struct AudioRing {
    buf: Mutex<VecDeque<u8>>,
    corked: AtomicBool,
    bytes_per_frame: usize,
    played_bytes: AtomicU64,
    max_bytes: usize,
}

impl AudioRing {
    pub fn new(sample_rate: u32, channels: u8, is_float: bool) -> Self {
        let sample_bytes = if is_float {
            PCM_FLOAT_BYTES
        } else {
            PCM_I16_BYTES
        };
        let bytes_per_frame = (channels as usize).max(1) * sample_bytes;
        let max_bytes = ((sample_rate as usize).max(MIN_RING_SAMPLE_RATE)
            / RING_BUFFER_DIVISOR_200MS)
            * bytes_per_frame;
        Self {
            buf: Mutex::new(VecDeque::with_capacity(max_bytes)),
            corked: AtomicBool::new(false),
            bytes_per_frame,
            played_bytes: AtomicU64::new(0),
            max_bytes,
        }
    }

    pub fn push_pcm(&self, data: &[u8]) {
        let mut guard = self.buf.lock().unwrap();
        while guard.len() + data.len() > self.max_bytes && !guard.is_empty() {
            let drop_n = (guard.len() + data.len() - self.max_bytes).min(guard.len());
            guard.drain(..drop_n);
        }
        guard.extend(data);
    }

    pub fn flush(&self) {
        self.buf.lock().unwrap().clear();
    }

    pub fn set_corked(&self, corked: bool) {
        self.corked.store(corked, Ordering::Relaxed);
    }

    pub fn max_bytes(&self) -> usize {
        self.max_bytes
    }

    pub fn played_bytes(&self) -> u64 {
        self.played_bytes.load(Ordering::Relaxed)
    }
}

extern "C" fn aaudio_data_cb(
    _stream: *mut AAudioStream,
    user_data: *mut c_void,
    audio_data: *mut c_void,
    num_frames: i32,
) -> i32 {
    if user_data.is_null() || audio_data.is_null() || num_frames <= 0 {
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }
    let ring = unsafe { &*(user_data as *const AudioRing) };
    let total_bytes = (num_frames as usize) * ring.bytes_per_frame;
    let out = unsafe { std::slice::from_raw_parts_mut(audio_data.cast::<u8>(), total_bytes) };

    if ring.corked.load(Ordering::Relaxed) {
        out.fill(0);
        return AAUDIO_CALLBACK_RESULT_CONTINUE;
    }

    let mut guard = ring.buf.lock().unwrap();
    let avail = guard.len().min(total_bytes);
    if avail > 0 {
        let (front, back) = guard.as_slices();
        let n1 = front.len().min(avail);
        out[..n1].copy_from_slice(&front[..n1]);
        if avail > n1 {
            let n2 = avail - n1;
            out[n1..avail].copy_from_slice(&back[..n2]);
        }
        guard.drain(..avail);
        ring.played_bytes.fetch_add(avail as u64, Ordering::Relaxed);
    }
    if avail < total_bytes {
        out[avail..].fill(0);
    }
    AAUDIO_CALLBACK_RESULT_CONTINUE
}

pub struct AAudioSink {
    stream: *mut AAudioStream,
    ring: Arc<AudioRing>,
    cb_ptr: *const AudioRing,
}

unsafe impl Send for AAudioSink {}

impl AAudioSink {
    pub fn open(sample_rate: u32, channels: u8, is_float: bool) -> Option<Self> {
        let ring = Arc::new(AudioRing::new(sample_rate, channels, is_float));
        let cb_ptr = Arc::into_raw(Arc::clone(&ring));

        unsafe {
            let mut builder: *mut AAudioStreamBuilder = std::ptr::null_mut();
            if AAudio_createStreamBuilder(&mut builder) != AAUDIO_OK || builder.is_null() {
                drop(Arc::from_raw(cb_ptr));
                error!("AAudio_createStreamBuilder failed");
                return None;
            }

            AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
            AAudioStreamBuilder_setSampleRate(builder, sample_rate as i32);
            AAudioStreamBuilder_setChannelCount(builder, channels as i32);
            AAudioStreamBuilder_setFormat(
                builder,
                if is_float {
                    AAUDIO_FORMAT_PCM_FLOAT
                } else {
                    AAUDIO_FORMAT_PCM_I16
                },
            );
            AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
            AAudioStreamBuilder_setDataCallback(builder, aaudio_data_cb, cb_ptr as *mut c_void);

            let mut stream: *mut AAudioStream = std::ptr::null_mut();
            let mut res = AAudioStreamBuilder_openStream(builder, &mut stream);
            if res != AAUDIO_OK || stream.is_null() {
                // Retry with unspecified performance mode on devices whose HAL refuses low-latency MMAP.
                AAudioStreamBuilder_setPerformanceMode(
                    builder,
                    AAUDIO_PERFORMANCE_MODE_UNSPECIFIED,
                );
                res = AAudioStreamBuilder_openStream(builder, &mut stream);
            }
            AAudioStreamBuilder_delete(builder);
            if res != AAUDIO_OK || stream.is_null() {
                drop(Arc::from_raw(cb_ptr));
                error!("AAudioStreamBuilder_openStream failed: {res}");
                return None;
            }

            let actual_rate = AAudioStream_getSampleRate(stream);
            let actual_ch = AAudioStream_getChannelCount(stream);
            let start_res = AAudioStream_requestStart(stream);
            if start_res != AAUDIO_OK {
                AAudioStream_close(stream);
                drop(Arc::from_raw(cb_ptr));
                error!("AAudioStream_requestStart failed: {start_res}");
                return None;
            }

            info!(
                "AAudio stream started: {} Hz, {} ch, float={}",
                actual_rate, actual_ch, is_float
            );
            Some(Self {
                stream,
                ring,
                cb_ptr,
            })
        }
    }

    pub fn ring(&self) -> &Arc<AudioRing> {
        &self.ring
    }
}

impl Drop for AAudioSink {
    fn drop(&mut self) {
        unsafe {
            if !self.stream.is_null() {
                AAudioStream_requestStop(self.stream);
                AAudioStream_close(self.stream);
                self.stream = std::ptr::null_mut();
            }
            if !self.cb_ptr.is_null() {
                drop(Arc::from_raw(self.cb_ptr));
            }
        }
    }
}
