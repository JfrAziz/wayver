//! Audio backend: PulseAudio Unix-socket server backed by Android AAudio.

mod aaudio_sink;
mod pulse_audio;

pub use pulse_audio::PulseAudioServer;
