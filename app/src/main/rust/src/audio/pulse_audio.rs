use std::collections::HashMap;
use std::fs;
use std::io::{Read, Write};
use std::os::unix::net::{UnixListener, UnixStream};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread::{self, JoinHandle};
use std::time::Duration;

use log::{error, info};

use super::aaudio_sink::AAudioSink;

const PA_INVALID_CHANNEL: u32 = 0xFFFF_FFFF;
// Native protocol v35 (PulseAudio 15.x). Masking the upper 16 bits on AUTH strips SHM/memfd
// capability flags so clients use plain Unix socket pstream framing.
const PA_PROTOCOL_VERSION: u32 = 35;
const PA_PROTOCOL_VERSION_MASK: u32 = 0xFFFF;

// PulseAudio pstream framing & server loop constants.
// Every pstream packet starts with a 20-byte big-endian descriptor (length, channel, offset_hi, offset_lo, flags).
const PA_PSTREAM_HEADER_BYTES: usize = 20;
const PA_MAX_FRAME_BYTES: usize = 4 * 1024 * 1024;
// Short socket read timeout lets the client loop wake periodically to send PA_COMMAND_REQUEST
// refills and observe server shutdown without busy-waiting.
const PA_CLIENT_READ_TIMEOUT_MS: u64 = 20;
const PA_ACCEPT_POLL_MS: u64 = 25;

// Audio stream defaults & refill thresholds.
const PA_DEFAULT_SAMPLE_RATE: u32 = 48_000;
const PA_MIN_SAMPLE_RATE: u32 = 8_000;
const PA_MAX_SAMPLE_RATE: u32 = 192_000;
const PA_DEFAULT_CHANNELS: u8 = 2;
const PA_MAX_CHANNELS: u8 = 8;
// 200 ms of 48 kHz stereo s16le (48000 / 5 * 4 bytes) used if stream state lookup misses.
const PA_FALLBACK_MAX_BYTES: u32 = 38_400;
// Batch refill requests between 1 KiB and 32 KiB to avoid tiny IPC packets or overflowing the ring.
const PA_MIN_REQUEST_BYTES: u32 = 1_024;
const PA_MAX_REQUEST_BYTES: u64 = 32_768;
// Nominal 20 ms sink latency reported to PulseAudio clients (in microseconds).
const PA_SINK_LATENCY_USEC: u64 = 20_000;
// PulseAudio fixed-point 100% (0 dB) volume and default volume step count (0x10000).
const PA_VOLUME_NORM: u32 = 65_536;
const PA_SINK_INDEX: u32 = 0;
const PA_CLIENT_INDEX: u32 = 1;
const PA_SINK_STATE_RUNNING: u32 = 0;
const PA_FORMAT_ENCODING_PCM: u8 = 1;

// PulseAudio tagstruct wire type tags (pulsecore/tagstruct.c).
const PA_TAG_STRING: u8 = b't';
const PA_TAG_STRING_NULL: u8 = b'N';
const PA_TAG_U32: u8 = b'L';
const PA_TAG_U8: u8 = b'B';
const PA_TAG_U64: u8 = b'R';
const PA_TAG_S64: u8 = b'r';
const PA_TAG_SAMPLE_SPEC: u8 = b'a';
const PA_TAG_TIMEVAL: u8 = b'T';
const PA_TAG_USEC: u8 = b'U';
const PA_TAG_CHANNEL_MAP: u8 = b'm';
const PA_TAG_CVOLUME: u8 = b'v';
const PA_TAG_PROPLIST: u8 = b'P';
const PA_TAG_VOLUME: u8 = b'V';
const PA_TAG_BOOLEAN_TRUE: u8 = b'1';
const PA_TAG_BOOLEAN_FALSE: u8 = b'0';
const PA_TAG_FORMAT_INFO: u8 = b'f';

// PulseAudio channel map positions (pulse/channelmap.h).
const PA_CHANNEL_POSITION_MONO: u8 = 0;
const PA_CHANNEL_POSITION_FRONT_LEFT: u8 = 1;
const PA_CHANNEL_POSITION_FRONT_RIGHT: u8 = 2;

// PulseAudio commands (pulsecore/native-common.h)
const PA_COMMAND_ERROR: u32 = 0;
const PA_COMMAND_REPLY: u32 = 2;
const PA_COMMAND_CREATE_PLAYBACK_STREAM: u32 = 3;
const PA_COMMAND_DELETE_PLAYBACK_STREAM: u32 = 4;
const PA_COMMAND_AUTH: u32 = 8;
const PA_COMMAND_SET_CLIENT_NAME: u32 = 9;
const PA_COMMAND_LOOKUP_SINK: u32 = 10;
const PA_COMMAND_DRAIN_PLAYBACK_STREAM: u32 = 12;
const PA_COMMAND_STAT: u32 = 13;
const PA_COMMAND_GET_PLAYBACK_LATENCY: u32 = 14;
const PA_COMMAND_GET_SERVER_INFO: u32 = 20;
const PA_COMMAND_GET_SINK_INFO: u32 = 21;
const PA_COMMAND_GET_SINK_INFO_LIST: u32 = 22;
const PA_COMMAND_SUBSCRIBE: u32 = 37;
const PA_COMMAND_SET_SINK_INPUT_VOLUME: u32 = 39;
const PA_COMMAND_CORK_PLAYBACK_STREAM: u32 = 43;
const PA_COMMAND_FLUSH_PLAYBACK_STREAM: u32 = 44;
const PA_COMMAND_TRIGGER_PLAYBACK_STREAM: u32 = 45;
const PA_COMMAND_SET_PLAYBACK_STREAM_NAME: u32 = 48;
const PA_COMMAND_REQUEST: u32 = 61;
const PA_COMMAND_PREBUF_PLAYBACK_STREAM: u32 = 62;
const PA_COMMAND_SET_PLAYBACK_STREAM_BUFFER_ATTR: u32 = 74;
const PA_COMMAND_UPDATE_PLAYBACK_STREAM_PROPLIST: u32 = 76;

const PA_ERR_NOTIMPLEMENTED: u32 = 18;

const PA_SAMPLE_S16LE: u8 = 3;
const PA_SAMPLE_FLOAT32LE: u8 = 5;

pub struct PulseAudioServer {
    socket_path: String,
    running: Arc<AtomicBool>,
    thread: Option<JoinHandle<()>>,
}

impl PulseAudioServer {
    pub fn start(runtime_dir: &str) -> Option<Self> {
        if runtime_dir.is_empty() {
            return None;
        }
        let socket_path = format!("{runtime_dir}/pulse-native");
        let _ = fs::remove_file(&socket_path);

        let listener = match UnixListener::bind(&socket_path) {
            Ok(l) => l,
            Err(e) => {
                error!("PulseAudio bind({socket_path}) failed: {e}");
                return None;
            }
        };
        let _ = listener.set_nonblocking(true);

        let running = Arc::new(AtomicBool::new(true));
        let r_flag = Arc::clone(&running);
        let sock_clone = socket_path.clone();

        let thread = thread::spawn(move || {
            info!("PulseAudio native server listening on {sock_clone}");
            while r_flag.load(Ordering::SeqCst) {
                match listener.accept() {
                    Ok((stream, _)) => {
                        let _ = stream.set_nonblocking(false);
                        let client_run = Arc::clone(&r_flag);
                        thread::spawn(move || handle_client(stream, client_run));
                    }
                    Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => {
                        thread::sleep(Duration::from_millis(PA_ACCEPT_POLL_MS));
                    }
                    Err(e) => {
                        error!("PulseAudio accept error: {e}");
                        break;
                    }
                }
            }
            info!("PulseAudio native server stopped");
        });

        Some(Self {
            socket_path,
            running,
            thread: Some(thread),
        })
    }
}

impl Drop for PulseAudioServer {
    fn drop(&mut self) {
        self.running.store(false, Ordering::SeqCst);
        if let Some(t) = self.thread.take() {
            let _ = t.join();
        }
        let _ = fs::remove_file(&self.socket_path);
    }
}

struct StreamState {
    sink: AAudioSink,
    written_bytes: u64,
    requested_until: u64,
}

fn handle_client(mut stream: UnixStream, running: Arc<AtomicBool>) {
    let _ = stream.set_read_timeout(Some(Duration::from_millis(PA_CLIENT_READ_TIMEOUT_MS)));
    let mut streams: HashMap<u32, StreamState> = HashMap::new();
    let mut next_channel = 0u32;
    let mut client_proto = PA_PROTOCOL_VERSION;

    while running.load(Ordering::SeqCst) {
        // Proactively keep each active stream's ring filled up to half capacity.
        for (&chan, st) in &mut streams {
            let ring = st.sink.ring();
            let target = ring.played_bytes() + (ring.max_bytes() as u64 / 2);
            if target > st.requested_until {
                let delta = (target - st.requested_until).min(PA_MAX_REQUEST_BYTES) as u32;
                if delta >= PA_MIN_REQUEST_BYTES {
                    let mut req = TagWriter::new();
                    req.put_u32(PA_COMMAND_REQUEST);
                    req.put_u32(0); // seq (unused for server->client notifications)
                    req.put_u32(chan);
                    req.put_u32(delta);
                    info!("PulseAudio: sending REQUEST chan={chan} delta={delta}");
                    if write_pstream(&mut stream, PA_INVALID_CHANNEL, &req.buf).is_err() {
                        return;
                    }
                    st.requested_until += delta as u64;
                }
            }
        }

        let mut hdr = [0u8; PA_PSTREAM_HEADER_BYTES];
        match stream.read_exact(&mut hdr) {
            Ok(()) => {}
            Err(e)
                if e.kind() == std::io::ErrorKind::WouldBlock
                    || e.kind() == std::io::ErrorKind::TimedOut =>
            {
                continue;
            }
            Err(_) => break,
        }

        let len = u32::from_be_bytes(hdr[0..4].try_into().unwrap()) as usize;
        let channel = u32::from_be_bytes(hdr[4..8].try_into().unwrap());
        if len > PA_MAX_FRAME_BYTES {
            break;
        }
        let mut payload = vec![0u8; len];
        if read_exact_blocking(&mut stream, &mut payload, &running).is_err() {
            break;
        }

        if channel != PA_INVALID_CHANNEL {
            info!("PulseAudio: PCM data chan={channel} len={}", payload.len());
            if let Some(st) = streams.get_mut(&channel) {
                st.sink.ring().push_pcm(&payload);
                st.written_bytes += payload.len() as u64;
            }
            continue;
        }

        let mut r = TagReader::new(&payload);
        let Some(cmd) = r.get_u32() else {
            info!(
                "PulseAudio: failed to parse cmd (len={len}, first_byte={:?})",
                payload.first()
            );
            continue;
        };
        let Some(seq) = r.get_u32() else {
            info!("PulseAudio: failed to parse seq for cmd={cmd}");
            continue;
        };
        info!("PulseAudio: cmd={cmd} seq={seq} len={len}");

        let mut reply = TagWriter::new();
        reply.put_u32(PA_COMMAND_REPLY);
        reply.put_u32(seq);

        match cmd {
            PA_COMMAND_AUTH => {
                if let Some(ver) = r.get_u32() {
                    let masked = ver & PA_PROTOCOL_VERSION_MASK;
                    client_proto = masked.min(PA_PROTOCOL_VERSION);
                    info!("PulseAudio AUTH: client_ver_raw=0x{ver:08x} masked={masked} -> negotiated={client_proto}");
                }
                // Reply with server protocol version (SHM/memfd flags cleared so plain sockets are used).
                reply.put_u32(client_proto);
                let _ = write_pstream(&mut stream, PA_INVALID_CHANNEL, &reply.buf);
            }
            PA_COMMAND_SET_CLIENT_NAME => {
                reply.put_u32(PA_CLIENT_INDEX);
                let _ = write_pstream(&mut stream, PA_INVALID_CHANNEL, &reply.buf);
            }
            PA_COMMAND_GET_SERVER_INFO => {
                reply.put_str("pulseaudio");
                reply.put_str("15.0.0");
                reply.put_str("wayver");
                reply.put_str("android");
                reply.put_sample_spec(PA_SAMPLE_S16LE, PA_DEFAULT_CHANNELS, PA_DEFAULT_SAMPLE_RATE);
                reply.put_str("wayver_sink");
                reply.put_str("wayver_sink.monitor");
                reply.put_u32(0); // default_sink_cookie (unused)
                reply.put_channel_map(PA_DEFAULT_CHANNELS);
                let _ = write_pstream(&mut stream, PA_INVALID_CHANNEL, &reply.buf);
            }
            PA_COMMAND_LOOKUP_SINK => {
                reply.put_u32(PA_SINK_INDEX);
                let _ = write_pstream(&mut stream, PA_INVALID_CHANNEL, &reply.buf);
            }
            PA_COMMAND_GET_SINK_INFO | PA_COMMAND_GET_SINK_INFO_LIST => {
                write_sink_info(&mut reply, client_proto);
                let _ = write_pstream(&mut stream, PA_INVALID_CHANNEL, &reply.buf);
            }
            PA_COMMAND_STAT => {
                for _ in 0..5 {
                    reply.put_u32(0);
                }
                let _ = write_pstream(&mut stream, PA_INVALID_CHANNEL, &reply.buf);
            }
            PA_COMMAND_CREATE_PLAYBACK_STREAM => {
                let (fmt, ch, rate) = r.find_sample_spec().unwrap_or((
                    PA_SAMPLE_S16LE,
                    PA_DEFAULT_CHANNELS,
                    PA_DEFAULT_SAMPLE_RATE,
                ));
                let channels = ch.clamp(1, PA_DEFAULT_CHANNELS);
                let sample_rate = rate.clamp(PA_MIN_SAMPLE_RATE, PA_MAX_SAMPLE_RATE);
                let is_float = fmt == PA_SAMPLE_FLOAT32LE;
                let actual_fmt = if is_float {
                    PA_SAMPLE_FLOAT32LE
                } else {
                    PA_SAMPLE_S16LE
                };
                info!("PulseAudio CREATE_PLAYBACK_STREAM: req_fmt={fmt} ch={channels} rate={sample_rate}");

                if let Some(sink) = AAudioSink::open(sample_rate, channels, is_float) {
                    let chan = next_channel;
                    next_channel += 1;
                    // Target half the ring buffer so AAudio always has ~100 ms buffered ahead.
                    let init_req = (sink.ring().max_bytes() / 2) as u32;

                    reply.put_u32(chan);
                    reply.put_u32(chan); // stream index
                    reply.put_u32(init_req); // missing / initially requested bytes
                                             // Protocol >= 9 adds server-negotiated buffer_attr fields.
                    if client_proto >= 9 {
                        reply.put_u32(sink.ring().max_bytes() as u32); // maxlength
                        reply.put_u32(init_req); // tlength
                        reply.put_u32(0); // prebuf (0 = start immediately without prebuffer stall)
                        reply.put_u32(PA_MIN_REQUEST_BYTES); // minreq
                    }
                    // Protocol >= 12 adds negotiated sample spec, channel map, and sink identity.
                    if client_proto >= 12 {
                        reply.put_sample_spec(actual_fmt, channels, sample_rate);
                        reply.put_channel_map(channels);
                        reply.put_u32(PA_SINK_INDEX);
                        reply.put_str("wayver_sink");
                        reply.put_bool(false); // suspended
                    }
                    // Protocol >= 15 adds configured sink latency.
                    if client_proto >= 15 {
                        reply.put_usec(PA_SINK_LATENCY_USEC);
                    }
                    // Protocol >= 21 adds negotiated pa_format_info.
                    if client_proto >= 21 {
                        reply.buf.push(PA_TAG_FORMAT_INFO);
                        reply.put_u8(PA_FORMAT_ENCODING_PCM);
                        reply.put_empty_proplist();
                    }
                    let _ = write_pstream(&mut stream, PA_INVALID_CHANNEL, &reply.buf);

                    streams.insert(
                        chan,
                        StreamState {
                            sink,
                            written_bytes: 0,
                            requested_until: init_req as u64,
                        },
                    );
                } else {
                    let mut err = TagWriter::new();
                    err.put_u32(PA_COMMAND_ERROR);
                    err.put_u32(seq);
                    err.put_u32(PA_ERR_NOTIMPLEMENTED);
                    let _ = write_pstream(&mut stream, PA_INVALID_CHANNEL, &err.buf);
                }
            }
            PA_COMMAND_DELETE_PLAYBACK_STREAM => {
                if let Some(chan) = r.get_u32() {
                    streams.remove(&chan);
                }
                let _ = write_pstream(&mut stream, PA_INVALID_CHANNEL, &reply.buf);
            }
            PA_COMMAND_CORK_PLAYBACK_STREAM => {
                if let Some(chan) = r.get_u32() {
                    let corked = r.get_bool().unwrap_or(false);
                    if let Some(st) = streams.get(&chan) {
                        st.sink.ring().set_corked(corked);
                    }
                }
                let _ = write_pstream(&mut stream, PA_INVALID_CHANNEL, &reply.buf);
            }
            PA_COMMAND_FLUSH_PLAYBACK_STREAM => {
                if let Some(chan) = r.get_u32() {
                    if let Some(st) = streams.get_mut(&chan) {
                        st.sink.ring().flush();
                    }
                }
                let _ = write_pstream(&mut stream, PA_INVALID_CHANNEL, &reply.buf);
            }
            PA_COMMAND_GET_PLAYBACK_LATENCY => {
                let chan = r.get_u32().unwrap_or(0);
                let (sec, usec) = r.get_timeval().unwrap_or((0, 0));
                let (written, played) = streams
                    .get(&chan)
                    .map(|s| (s.written_bytes, s.sink.ring().played_bytes()))
                    .unwrap_or((0, 0));
                // Wire layout verified against libpulse stream_get_timing_info_callback:
                // sink_usec, source_usec, playing, timestamp, synchronized_clocks, write_index,
                // read_index, and (for proto >= 13) two u64 configured latencies (no since_underrun).
                reply.put_usec(PA_SINK_LATENCY_USEC); // sink_usec
                reply.put_usec(0); // source_usec
                reply.put_bool(true); // playing
                reply.put_timeval(sec, usec); // timestamp
                reply.put_timeval(sec, usec); // synchronized_clocks
                reply.put_s64(written as i64); // write_index
                reply.put_s64(played as i64); // read_index
                if client_proto >= 13 {
                    reply.put_u64(PA_SINK_LATENCY_USEC); // configured_sink_usec
                    reply.put_u64(0); // configured_source_usec
                }
                let _ = write_pstream(&mut stream, PA_INVALID_CHANNEL, &reply.buf);
            }
            PA_COMMAND_SET_PLAYBACK_STREAM_BUFFER_ATTR => {
                let chan = r.get_u32().unwrap_or(0);
                let max_bytes = streams
                    .get(&chan)
                    .map(|s| s.sink.ring().max_bytes() as u32)
                    .unwrap_or(PA_FALLBACK_MAX_BYTES);
                let tlength = max_bytes / 2;
                reply.put_u32(max_bytes);
                reply.put_u32(tlength);
                reply.put_u32(0); // prebuf
                reply.put_u32(PA_MIN_REQUEST_BYTES); // minreq
                reply.put_usec(PA_SINK_LATENCY_USEC);
                let _ = write_pstream(&mut stream, PA_INVALID_CHANNEL, &reply.buf);
            }
            PA_COMMAND_DRAIN_PLAYBACK_STREAM
            | PA_COMMAND_SUBSCRIBE
            | PA_COMMAND_SET_SINK_INPUT_VOLUME
            | PA_COMMAND_PREBUF_PLAYBACK_STREAM
            | PA_COMMAND_TRIGGER_PLAYBACK_STREAM
            | PA_COMMAND_SET_PLAYBACK_STREAM_NAME
            | PA_COMMAND_UPDATE_PLAYBACK_STREAM_PROPLIST => {
                let _ = write_pstream(&mut stream, PA_INVALID_CHANNEL, &reply.buf);
            }
            _ => {
                let mut err = TagWriter::new();
                err.put_u32(PA_COMMAND_ERROR);
                err.put_u32(seq);
                err.put_u32(PA_ERR_NOTIMPLEMENTED);
                let _ = write_pstream(&mut stream, PA_INVALID_CHANNEL, &err.buf);
            }
        }
    }
}

fn read_exact_blocking(
    stream: &mut UnixStream,
    buf: &mut [u8],
    running: &AtomicBool,
) -> std::io::Result<()> {
    let mut off = 0;
    while off < buf.len() && running.load(Ordering::SeqCst) {
        match stream.read(&mut buf[off..]) {
            Ok(0) => return Err(std::io::Error::from(std::io::ErrorKind::UnexpectedEof)),
            Ok(n) => off += n,
            Err(e)
                if e.kind() == std::io::ErrorKind::WouldBlock
                    || e.kind() == std::io::ErrorKind::TimedOut =>
            {
                continue;
            }
            Err(e) => return Err(e),
        }
    }
    if off == buf.len() {
        Ok(())
    } else {
        Err(std::io::Error::from(std::io::ErrorKind::Interrupted))
    }
}

fn write_pstream(stream: &mut UnixStream, channel: u32, payload: &[u8]) -> std::io::Result<()> {
    let mut hdr = [0u8; PA_PSTREAM_HEADER_BYTES];
    hdr[0..4].copy_from_slice(&(payload.len() as u32).to_be_bytes());
    hdr[4..8].copy_from_slice(&channel.to_be_bytes());
    stream.write_all(&hdr)?;
    stream.write_all(payload)?;
    stream.flush()
}

fn write_sink_info(w: &mut TagWriter, proto: u32) {
    w.put_u32(PA_SINK_INDEX);
    w.put_str("wayver_sink");
    w.put_str("Wayver AAudio Sink");
    w.put_sample_spec(PA_SAMPLE_S16LE, PA_DEFAULT_CHANNELS, PA_DEFAULT_SAMPLE_RATE);
    w.put_channel_map(PA_DEFAULT_CHANNELS);
    w.put_u32(0); // module index
    w.put_cvolume(PA_DEFAULT_CHANNELS, PA_VOLUME_NORM);
    w.put_bool(false); // muted
    w.put_u32(0); // monitor source index
    w.put_str("wayver_sink.monitor");
    w.put_usec(PA_SINK_LATENCY_USEC);
    w.put_str("module-aaudio-sink");
    w.put_u32(0); // sink flags
                  // Protocol >= 13 adds sink proplist and requested latency.
    if proto >= 13 {
        w.put_empty_proplist();
        w.put_usec(PA_SINK_LATENCY_USEC);
    }
    // Protocol >= 15 adds base_volume, state, n_volume_steps, and card index.
    if proto >= 15 {
        w.put_volume(PA_VOLUME_NORM);
        w.put_u32(PA_SINK_STATE_RUNNING);
        w.put_u32(PA_VOLUME_NORM); // n_volume_steps
        w.put_u32(0); // card index
    }
    // Protocol >= 16 adds device ports list.
    if proto >= 16 {
        w.put_u32(0); // n_ports
        w.put_str_null(); // active_port
    }
    // Protocol >= 21 adds supported formats list.
    if proto >= 21 {
        w.put_u8(1); // n_formats
        w.buf.push(PA_TAG_FORMAT_INFO);
        w.put_u8(PA_FORMAT_ENCODING_PCM);
        w.put_empty_proplist();
    }
}

struct TagWriter {
    buf: Vec<u8>,
}

impl TagWriter {
    fn new() -> Self {
        Self {
            buf: Vec::with_capacity(128),
        }
    }

    fn put_u8(&mut self, v: u8) {
        self.buf.push(PA_TAG_U8);
        self.buf.push(v);
    }

    fn put_u32(&mut self, v: u32) {
        self.buf.push(PA_TAG_U32);
        self.buf.extend_from_slice(&v.to_be_bytes());
    }

    fn put_s64(&mut self, v: i64) {
        self.buf.push(PA_TAG_S64);
        self.buf.extend_from_slice(&v.to_be_bytes());
    }

    fn put_u64(&mut self, v: u64) {
        self.buf.push(PA_TAG_U64);
        self.buf.extend_from_slice(&v.to_be_bytes());
    }

    fn put_usec(&mut self, v: u64) {
        self.buf.push(PA_TAG_USEC);
        self.buf.extend_from_slice(&v.to_be_bytes());
    }

    fn put_timeval(&mut self, sec: u32, usec: u32) {
        self.buf.push(PA_TAG_TIMEVAL);
        self.buf.extend_from_slice(&sec.to_be_bytes());
        self.buf.extend_from_slice(&usec.to_be_bytes());
    }

    fn put_bool(&mut self, v: bool) {
        self.buf.push(if v {
            PA_TAG_BOOLEAN_TRUE
        } else {
            PA_TAG_BOOLEAN_FALSE
        });
    }

    fn put_str(&mut self, s: &str) {
        self.buf.push(PA_TAG_STRING);
        self.buf.extend_from_slice(s.as_bytes());
        self.buf.push(0);
    }

    fn put_str_null(&mut self) {
        self.buf.push(PA_TAG_STRING_NULL);
    }

    fn put_sample_spec(&mut self, fmt: u8, channels: u8, rate: u32) {
        self.buf.push(PA_TAG_SAMPLE_SPEC);
        self.buf.push(fmt);
        self.buf.push(channels);
        self.buf.extend_from_slice(&rate.to_be_bytes());
    }

    fn put_channel_map(&mut self, channels: u8) {
        self.buf.push(PA_TAG_CHANNEL_MAP);
        self.buf.push(channels);
        if channels == 1 {
            self.buf.push(PA_CHANNEL_POSITION_MONO);
        } else {
            self.buf.push(PA_CHANNEL_POSITION_FRONT_LEFT);
            self.buf.push(PA_CHANNEL_POSITION_FRONT_RIGHT);
            for i in 2..channels {
                self.buf.push(i);
            }
        }
    }

    fn put_volume(&mut self, v: u32) {
        self.buf.push(PA_TAG_VOLUME);
        self.buf.extend_from_slice(&v.to_be_bytes());
    }

    fn put_cvolume(&mut self, channels: u8, vol: u32) {
        self.buf.push(PA_TAG_CVOLUME);
        self.buf.push(channels);
        for _ in 0..channels {
            self.buf.extend_from_slice(&vol.to_be_bytes());
        }
    }

    fn put_empty_proplist(&mut self) {
        self.buf.push(PA_TAG_PROPLIST);
        self.buf.push(PA_TAG_STRING_NULL);
    }
}

struct TagReader<'a> {
    data: &'a [u8],
    pos: usize,
}

impl<'a> TagReader<'a> {
    fn new(data: &'a [u8]) -> Self {
        Self { data, pos: 0 }
    }

    fn get_u32(&mut self) -> Option<u32> {
        if self.pos + 5 > self.data.len() || self.data[self.pos] != PA_TAG_U32 {
            return None;
        }
        let v = u32::from_be_bytes(self.data[self.pos + 1..self.pos + 5].try_into().ok()?);
        self.pos += 5;
        Some(v)
    }

    fn get_bool(&mut self) -> Option<bool> {
        if self.pos >= self.data.len() {
            return None;
        }
        let b = self.data[self.pos];
        self.pos += 1;
        match b {
            PA_TAG_BOOLEAN_TRUE => Some(true),
            PA_TAG_BOOLEAN_FALSE => Some(false),
            _ => None,
        }
    }

    fn get_timeval(&mut self) -> Option<(u32, u32)> {
        if self.pos + 9 > self.data.len() || self.data[self.pos] != PA_TAG_TIMEVAL {
            return None;
        }
        let sec = u32::from_be_bytes(self.data[self.pos + 1..self.pos + 5].try_into().ok()?);
        let usec = u32::from_be_bytes(self.data[self.pos + 5..self.pos + 9].try_into().ok()?);
        self.pos += 9;
        Some((sec, usec))
    }

    fn find_sample_spec(&mut self) -> Option<(u8, u8, u32)> {
        while self.pos + 7 <= self.data.len() {
            if self.data[self.pos] == PA_TAG_SAMPLE_SPEC {
                let fmt = self.data[self.pos + 1];
                let ch = self.data[self.pos + 2];
                let rate =
                    u32::from_be_bytes(self.data[self.pos + 3..self.pos + 7].try_into().ok()?);
                if (1..=PA_MAX_CHANNELS).contains(&ch)
                    && (PA_MIN_SAMPLE_RATE..=PA_MAX_SAMPLE_RATE).contains(&rate)
                {
                    self.pos += 7;
                    return Some((fmt, ch, rate));
                }
            }
            self.pos += 1;
        }
        None
    }
}
