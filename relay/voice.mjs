// PC voice for Manga Loud: turns one bubble into acted speech with a voice server (OmniVoice)
// on this PC. The phone sends text, tone and speaker; this returns a WAV clip.
//
// Each speaker type (narrator, man, girl...) gets one reference clip, made once with
// the voice server's voice design and kept in relay/voices. Every later line for that speaker is
// cloned from it. Delete a clip to get a new voice.
//
// Named characters go further: the first time a series' character speaks, it is given one
// of several voices for its speaker type (man-v1, man-v2...) and keeps it for the series.
// The choices are saved in relay/voices/cast/<series>.json, where they can be edited. Add
// "voice": "male, teenager, low pitch" to a character there to design a voice just for them,
// "soft": true for a gentler voice that also acts loud lines with restraint, and "playful": true
// for the same voice acted in a bouncy, childish way.
//
// Needs the voice server's API key in relay/voice-key.txt (or VOICE_SERVER_KEY).
// VOICE_SERVER_URL defaults to https://127.0.0.1:8000. The README describes the API it expects.
//
// The "fish" model reads lines with Fish Audio's cloud API instead, cloning the same reference
// clips. It needs a Fish Audio API key in relay/fish-key.txt (or FISH_API_KEY). FISH_MODEL picks
// Fish's model, s2.1-pro-free by default (s2.1-pro is the paid one).

import { createHash, randomBytes } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, readdirSync, renameSync, statSync, unlinkSync, writeFileSync } from 'node:fs';
import { request } from 'node:https';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const VOICE_URL = new URL(process.env.VOICE_SERVER_URL || 'https://127.0.0.1:8000');
const CAST_DIR = join(here, 'voices');
const CACHE_DIR = join(here, 'cache', 'voice');
const TIMEOUT_MS = 90 * 1000;
const MAX_TEXT = 1000;
const CACHE_MAX_FILES = 3000;
const CACHE_KEEP_FILES = 2500;

// The line each speaker type reads once to become its reference voice.
const REFERENCE_LINE = 'The old bridge creaked in the wind, and I stopped to look back at the town below.';
// A soft voice reads a warm, friendly line, and every later line copies that delivery. It is read
// at a normal pace and steady sampling: slow, low-temperature reads came out breathy and husky.
const SOFT_REFERENCE_LINE = "It's all right. Take your time, I'll wait here with you until the rain stops.";
const SOFT_REFERENCE = { num_step: 32, guidance_scale: 2.0, class_temperature: 0.4, position_temperature: 5.0, speed: 1.0 };

// A playful character keeps their voice but copies a bouncy, childish read of this line, made by
// cloning their own clip with lively settings.
const PLAYFUL_REFERENCE_LINE = "Hey, hey, look at this! I made it all by myself, and it's the best one ever!";
const PLAYFUL_REFERENCE = { num_step: 32, guidance_scale: 1.7, class_temperature: 0.85, position_temperature: 6.5, speed: 1.06 };

const NATURAL = { num_step: 32, guidance_scale: 2.0, class_temperature: 0.4, position_temperature: 5.0, speed: 1.0 };
const EXPRESSIVE = { num_step: 32, guidance_scale: 1.8, class_temperature: 0.7, position_temperature: 6.0, speed: 0.97 };
const EXPRESSIVE_PLUS = { num_step: 32, guidance_scale: 1.7, class_temperature: 0.85, position_temperature: 6.5, speed: 0.97 };
const NARRATION = { num_step: 32, guidance_scale: 2.2, class_temperature: 0.3, position_temperature: 4.0, speed: 0.95 };

// Matches VoiceStyle.SPEAKERS in the app. instruct is OmniVoice's voice design description.
const SPEAKERS = {
  narrator: 'male, middle-aged, low pitch',
  system: 'male, young adult, moderate pitch',
  man: 'male, young adult',
  woman: 'female, young adult',
  boy: 'male, child, high pitch',
  girl: 'female, teenager',
  'old man': 'male, elderly, low pitch',
  'old woman': 'female, elderly',
  creature: 'male, very low pitch',
  unknown: 'male, young adult',
};

// Extra voices for named characters, varied by age and pitch. Voice design also varies the
// timbre each time, so two voices from the same description still sound like two people.
const VARIANTS = {
  man: ['male, young adult, low pitch', 'male, young adult, high pitch', 'male, middle-aged', 'male, young adult, moderate pitch', 'male, middle-aged, low pitch'],
  woman: ['female, young adult, high pitch', 'female, young adult, low pitch', 'female, middle-aged', 'female, young adult, moderate pitch'],
  boy: ['male, teenager', 'male, child', 'male, teenager, high pitch'],
  girl: ['female, child, high pitch', 'female, teenager, high pitch', 'female, teenager, moderate pitch'],
  'old man': ['male, elderly, very low pitch', 'male, elderly', 'male, elderly, moderate pitch'],
  'old woman': ['female, elderly, low pitch', 'female, elderly, high pitch'],
  creature: ['male, very low pitch', 'female, very low pitch', 'male, low pitch'],
  unknown: ['male, young adult, low pitch', 'male, young adult, high pitch', 'male, middle-aged'],
};
const CAST_LIST_DIR = join(CAST_DIR, 'cast');
const MAX_NAME = 60;
const MAX_SERIES = 200;

// Matches VoiceStyle.TONES in the app.
const TONES = {
  neutral: { ...NATURAL },
  happy: { ...EXPRESSIVE, speed: 1.0 },
  excited: { ...EXPRESSIVE_PLUS, speed: 1.03 },
  shouting: { ...EXPRESSIVE_PLUS },
  angry: { ...EXPRESSIVE_PLUS },
  sad: { ...EXPRESSIVE, speed: 0.88 },
  scared: { ...EXPRESSIVE_PLUS, speed: 1.02 },
  whispering: { ...EXPRESSIVE, speed: 0.95, instruct: 'whisper' },
  thinking: { ...EXPRESSIVE, speed: 0.92 },
  serious: { ...NATURAL, speed: 0.96 },
  laughing: { ...EXPRESSIVE_PLUS, speed: 1.03 },
  teasing: { ...EXPRESSIVE, speed: 1.0 },
  cold: { ...NATURAL, guidance_scale: 2.2, class_temperature: 0.3, speed: 0.93 },
  crying: { ...EXPRESSIVE_PLUS, speed: 0.88 },
  desperate: { ...EXPRESSIVE_PLUS, speed: 1.05 },
  pained: { ...EXPRESSIVE_PLUS, speed: 0.9 },
  tired: { ...EXPRESSIVE, class_temperature: 0.55, speed: 0.88 },
  embarrassed: { ...EXPRESSIVE, speed: 1.03 },
};

/**
 * How strongly a line is acted: 1 pulls the tone's settings halfway back towards a plain read,
 * 2 leaves them, 3 pushes them further. Neutral lines are plain at any intensity.
 */
function withIntensity(style, intensity) {
  if (intensity === 2) return style;
  const amount = intensity === 1 ? 0.5 : 1.5;
  const towards = (key, min, max) => {
    const value = NATURAL[key] + (style[key] - NATURAL[key]) * amount;
    return Number(Math.min(max, Math.max(min, value)).toFixed(3));
  };
  return {
    ...style,
    guidance_scale: towards('guidance_scale', 1.4, 2.4),
    class_temperature: towards('class_temperature', 0.2, 1.0),
    position_temperature: towards('position_temperature', 3.5, 7.5),
    speed: towards('speed', 0.75, 1.2),
  };
}

export const QUALITIES = { normal: 32, fast: 16 };

/**
 * The voice server's speech models. OmniVoice (the default) is acted with the settings above. The
 * other two clone the character's reference clip and take no instruct: Qwen3-TTS is acted with
 * an expression preset and strength, VibeVoice follows the reference clip's own delivery.
 */
export const MODELS = ['omnivoice', 'qwen3-tts', 'vibevoice', 'fish'];

/** Kinds of text the app can send; anything else is spoken as dialogue. */
const KINDS = ['dialogue', 'thought', 'narration', 'system', 'reaction'];

// Each tone as one of the voice server's expression presets (neutral, calm, gentle, serious, sad,
// happy, excited, angry, expressive, dramatic), with how strongly the preset is applied.
const TONE_EXPRESSIONS = {
  neutral: ['neutral', 1.0],
  happy: ['happy', 1.0],
  excited: ['excited', 1.0],
  shouting: ['dramatic', 1.1],
  angry: ['angry', 1.0],
  sad: ['sad', 1.0],
  scared: ['dramatic', 0.9],
  whispering: ['gentle', 1.0],
  thinking: ['calm', 1.0],
  serious: ['serious', 1.0],
  laughing: ['happy', 1.3],
  teasing: ['expressive', 0.9],
  cold: ['serious', 1.2],
  crying: ['sad', 1.4],
  desperate: ['dramatic', 1.2],
  pained: ['dramatic', 0.9],
  tired: ['calm', 1.2],
  embarrassed: ['gentle', 1.1],
};
// Intensity 1 to 3 scales the strength: held back, as written, pushed.
const INTENSITY_STRENGTH = { 1: 0.7, 2: 1.0, 3: 1.4 };

/** The expression preset and strength (0 to 2) for one line. */
function expressionFor(tone, intensity, speaker, soft, playful) {
  if (speaker === 'narrator' && tone === 'neutral') return { expression: 'calm', strength: 1.0 };
  const [expression, base] = TONE_EXPRESSIONS[tone] ?? ['neutral', 1.0];
  let strength = base * (INTENSITY_STRENGTH[intensity] ?? 1.0);
  if (soft) strength *= 0.75;
  if (playful) strength += 0.2;
  return { expression, strength: Number(Math.min(2, Math.max(0, strength)).toFixed(2)) };
}

// Each tone as a Fish Audio S2 cue, put in front of the line. Cues are free-form; most of these
// are from Fish's emotion list. Feelings take "slightly" or "very" from the intensity, while
// delivery cues (whispering, shouting) and sounds (laughing, sobbing) are used as they are.
const FISH_CUES = {
  happy: 'happy',
  excited: 'excited',
  angry: 'angry',
  sad: 'sad',
  scared: 'scared',
  thinking: 'thoughtful',
  serious: 'serious',
  teasing: 'teasing',
  cold: 'cold',
  desperate: 'desperate',
  pained: 'in pain',
  tired: 'tired',
  embarrassed: 'embarrassed',
};
const FISH_FIXED_CUES = { shouting: 'shouting', whispering: 'whispering', laughing: 'laughing', crying: 'sobbing' };
const FISH_INTENSITY = { 1: 'slightly ', 2: '', 3: 'very ' };

/**
 * Fish reads anything in brackets as a cue, not words: "(Approaching Doom (Hidden))", a system
 * box, came out as 41 seconds of noise. Brackets in the bubble become pauses instead, so it
 * reads "Approaching Doom, Hidden".
 */
// Skill names often come in other brackets ("⟨Attack Memorial Song⟩!"), so all kinds count.
const BRACKETS = /[[\](){}<>|⟨⟩〈〉《》「」『』【】〔〕［］（）｛｝‹›«»〖〗〘〙⦅⦆⟦⟧⟪⟫❨❩❪❫❬❭❮❯]/g;

export function fishWords(text) {
  return text
    .replace(BRACKETS, ' , ')
    .replace(/\s*,(\s*,)*\s*/g, ', ')
    .replace(/,\s*([.!?…])/g, '$1')
    .replace(/^[,\s]+|[,\s]+$/g, '')
    .trim();
}

/** Sounds a character makes instead of words. Matches the app's list and the reading guide's. */
export const SOUNDS = ['cough', 'shriek', 'groan', 'sigh', 'gasp', 'laugh', 'sob', 'pant', 'grunt'];

// Each sound as words, for lines from apps that do not label sounds: every word of the line (or
// of its opening, before the first mark) must be one of these. Whole words only, so "I have a
// cough" is speech.
const SOUND_WORDS = {
  cough: /^(cough|coughs|koff|kof|cof|khh?|kehh?|ahem)$/,
  shriek: /^(k+y+a+h*|ki+ya+h*|e{2,}k+|hi{2,}e*h*|i+ya{2,}h*)$/,
  groan: /^(a*r*g+h+|a+g+h*|u+g+h+|gu+h+|ga+h+|n+g+h+|ku+h+|kugh|urk)$/,
  sigh: /^(ha{2,}h*|ho{2,}h*|phew|sigh|sighs|fu{2,}h*|hu{2,}h*)$/,
  gasp: /^(gasp|gasps|gulp)$/,
  laugh: /^(a?(ha){2,}h*|(he){2,}h*|(hi){2,}h*|(fu){2,}h*|(ku){2,}h*|(ho){2,}h*|(ke){2,}h*)$/,
  sob: /^(sob|sobs|hic|hik|sniff|sniffs|sniffle|uwa+h*|wa{3,}h*)$/,
  pant: /^(huff|puff|pant|pants|hff)$/,
  grunt: /^(hmph|humph|tch|tsk|grr+|hn+|nn+)$/,
};

const soundOfWords = (words) => {
  const tokens = words.toLowerCase().split(/[^a-z]+/).filter(Boolean);
  if (tokens.length === 0) return '';
  return SOUNDS.find((name) => tokens.every((t) => SOUND_WORDS[name].test(t))) ?? '';
};

/**
 * Splits a line into the sound a character makes and the words after it: "Cough! I'm fine"
 * is a cough, then "I'm fine". A known sound (from the app) wins; otherwise the words decide.
 */
export function splitSound(text, sound = '') {
  // The app saw the picture, so its sound wins ("Haah, haah..." panting, not a sigh).
  if (SOUNDS.includes(sound)) return { sound, said: text, rest: '' };
  const whole = soundOfWords(text);
  if (whole) return { sound: whole, said: text, rest: '' };
  const lead = text.match(/^\s*([A-Za-z' -]+?)([!?.…,~]+)\s*(.*\p{L}.*)$/u);
  const leading = lead ? soundOfWords(lead[1]) : '';
  if (leading) return { sound: leading, said: lead[1] + lead[2], rest: lead[3] };
  return { sound: '', said: '', rest: text };
}

/** The Fish cue for a sound, and what to read with it, given how the line feels. */
function fishSound(sound, said, tone) {
  switch (sound) {
    // "Cough!" read out is the word; a cue with a throat noise is a cough.
    // Listening tests: "[coughing] Uh..." sounded like a real cough; "Cough!" was read as the word.
    case 'cough': return '[coughing] Uh...';
    // Listening tests: the scream cue beat a squeal or no cue for "Kyaa!", and a plain "Agh!" beat
    // "[in pain]"; only an angry "Argh!" sounded better with a cue.
    case 'shriek': return `[screaming] ${said}`;
    case 'groan': return tone === 'angry' ? `[frustrated] ${said}` : said;
    case 'sigh': return `[sighing] ${said}`;
    case 'gasp': return '[gasping] Ah!';
    case 'laugh': return `[laughing] ${said}`;
    case 'sob': return `[sobbing] ${said}`;
    case 'pant': return `[panting] ${said}`;
    default: return said;
  }
}

/** The tone cues for spoken words. */
const URGENT_TONES = ['shouting', 'angry', 'scared', 'desperate', 'excited'];

/** A thought said with feeling ("It's him!"), not inwardly. Matches VoiceStyle.isQuietThought. */
export const isUrgentThought = (tone, text) => URGENT_TONES.includes(tone) || /!\s*$/.test(text);

function fishCues(tone, intensity, speaker, soft, playful, kind, text) {
  const cues = [];
  const urgent = isUrgentThought(tone, text);
  if (FISH_FIXED_CUES[tone]) cues.push(FISH_FIXED_CUES[tone]);
  else if (FISH_CUES[tone]) cues.push((FISH_INTENSITY[intensity] ?? '') + FISH_CUES[tone]);
  else if (speaker === 'narrator' || kind === 'system') cues.push('calm');
  // A thought is said inwardly, unless it is an urgent one ("It's him!").
  if (kind === 'thought' && !urgent && tone !== 'whispering') cues.push('quietly, to self');
  if (soft && tone !== 'whispering') cues.push('soft tone');
  if (playful) cues.push('playful');
  return cues.map((cue) => `[${cue}]`).join('');
}

/** The line as Fish Audio reads it: sounds as sound cues, and the tone as bracket cues in front. */
export function fishText(text, tone, intensity, speaker, soft, playful, kind = '', sound = '') {
  text = fishWords(text) || text.replace(BRACKETS, ' ').trim();
  const split = splitSound(text, kind === 'reaction' ? sound : '');
  const parts = [];
  // "Cough! I'm fine": the cue alone, then the words, sounded best ("[coughing] I'm fine.").
  if (split.sound === 'cough' && split.rest) parts.push('[coughing]');
  else if (split.sound) parts.push(fishSound(split.sound, split.said, tone));
  if (split.rest) {
    const cues = fishCues(tone, intensity, speaker, soft, playful, kind, split.rest);
    parts.push(cues + (cues ? ' ' : '') + split.rest);
  }
  return parts.join(' ');
}

/** The smallest MessagePack encoder for Fish's request: maps, arrays, strings, bytes, numbers, booleans. */
export function msgpack(value) {
  const parts = [];
  const head = (small, codes, length) => {
    if (small !== null && length < 16) return parts.push(Buffer.from([small | length]));
    const [c8, c16, c32] = codes;
    if (c8 !== null && length < 0x100) return parts.push(Buffer.from([c8, length]));
    if (length < 0x10000) { const b = Buffer.alloc(3); b[0] = c16; b.writeUInt16BE(length, 1); return parts.push(b); }
    const b = Buffer.alloc(5); b[0] = c32; b.writeUInt32BE(length, 1); parts.push(b);
  };
  const write = (v) => {
    if (v === null || v === undefined) parts.push(Buffer.from([0xc0]));
    else if (v === true || v === false) parts.push(Buffer.from([v ? 0xc3 : 0xc2]));
    else if (typeof v === 'number') {
      if (Number.isInteger(v) && v >= 0 && v < 0x80) parts.push(Buffer.from([v]));
      else if (Number.isInteger(v) && v >= 0 && v <= 0xffffffff) { const b = Buffer.alloc(5); b[0] = 0xce; b.writeUInt32BE(v, 1); parts.push(b); }
      else { const b = Buffer.alloc(9); b[0] = 0xcb; b.writeDoubleBE(v, 1); parts.push(b); }
    } else if (typeof v === 'string') {
      const bytes = Buffer.from(v, 'utf8');
      if (bytes.length < 32) parts.push(Buffer.from([0xa0 | bytes.length]));
      else head(null, [0xd9, 0xda, 0xdb], bytes.length);
      parts.push(bytes);
    } else if (Buffer.isBuffer(v)) {
      head(null, [0xc4, 0xc5, 0xc6], v.length);
      parts.push(v);
    } else if (Array.isArray(v)) {
      head(0x90, [null, 0xdc, 0xdd], v.length);
      v.forEach(write);
    } else {
      const entries = Object.entries(v).filter(([, x]) => x !== undefined);
      head(0x80, [null, 0xde, 0xdf], entries.length);
      for (const [k, x] of entries) { write(k); write(x); }
    }
  };
  write(value);
  return Buffer.concat(parts);
}

const FISH_URL = new URL(process.env.FISH_URL || 'https://api.fish.audio/v1/tts');
const FISH_MODEL = process.env.FISH_MODEL || 's2.1-pro-free';
let cachedFishKey = null;
function fishKey() {
  if (cachedFishKey) return cachedFishKey;
  const file = ['fish-key.txt', 'fish-key'].map((name) => join(here, name)).find(existsSync);
  const key = (process.env.FISH_API_KEY || (file && readFileSync(file, 'utf8')) || '').trim();
  if (!key) {
    throw Object.assign(new Error('Fish Audio is not set up. Save a Fish Audio API key in relay/fish-key.txt.'), { status: 503 });
  }
  cachedFishKey = key;
  return key;
}

/**
 * Fish streams its WAV with placeholder sizes in the header (0xFFFFFF..), since it does not know
 * the length up front. Some players refuse that, so the real sizes are written in.
 */
export function fixWavSizes(wav) {
  if (wav.length < 12 || wav.toString('latin1', 0, 4) !== 'RIFF' || wav.toString('latin1', 8, 12) !== 'WAVE') return wav;
  let at = 12;
  while (at + 8 <= wav.length) {
    const id = wav.toString('latin1', at, at + 4);
    if (id === 'data') {
      wav.writeUInt32LE(wav.length - at - 8, at + 4);
      wav.writeUInt32LE(wav.length - 8, 4);
      break;
    }
    const size = wav.readUInt32LE(at + 4);
    at += 8 + size + (size & 1);
  }
  return wav;
}

/** Seconds of sound in a 16-bit WAV, or 0 when it cannot tell. */
export function wavSeconds(wav) {
  if (wav.length < 44 || wav.toString('latin1', 0, 4) !== 'RIFF') return 0;
  const channels = wav.readUInt16LE(22);
  const rate = wav.readUInt32LE(24);
  const bits = wav.readUInt16LE(34);
  if (!channels || !rate || !bits) return 0;
  return (wav.length - 44) / (rate * channels * (bits / 8));
}

/**
 * The longest a clip of this line should be. Fish sometimes runs on, babbling well past the
 * words (a 41-second clip in testing), so a clip far over this is made again. About twice the
 * line's length at a normal 14 characters a second, plus 2.5 seconds, and never under 4 seconds (a
 * real laugh or sob takes a moment): "Haa, haa" gets 4 seconds, a 150-character line about 24. Matches Wav.limitSeconds in the app.
 */
export function fishLimitSeconds(text, speed) {
  return Math.max(4, 2.5 + text.length / (7 * speed));
}

/** One line from Fish Audio, made again once if it runs far longer than the words need. */
async function callFishChecked(text, spoken, speed, ref) {
  const limit = fishLimitSeconds(spoken, speed);
  for (let attempt = 1; ; attempt++) {
    // The retry sends only the words: cues in front of short lines are what set Fish off
    // ("[grunting] Tsk" once came back as 43 seconds of noise).
    const audio = attempt > 1
      ? await callFish(fishWords(spoken) || spoken, speed, ref, 0.5)
      : await callFish(text, speed, ref);
    const seconds = wavSeconds(audio);
    if (seconds <= limit) return audio;
    console.log(`[${new Date().toLocaleTimeString()}] Fish clip ran on: ${seconds.toFixed(1)}s for ${spoken.length} characters (limit ${limit.toFixed(1)}s), attempt ${attempt}`);
    if (attempt >= 2) {
      throw Object.assign(new Error(`Fish Audio made a ${Math.round(seconds)}s clip for a short line`), { status: 502 });
    }
  }
}

/** One line from Fish Audio's TTS API, cloned from the reference clip. Returns WAV bytes. */
function callFish(text, speed, ref, temperature) {
  const body = msgpack({
    text,
    temperature,
    references: [{ audio: ref.audio, text: ref.refText }],
    format: 'wav',
    sample_rate: 24000,
    latency: 'normal',
    normalize: true,
    language: 'en',
    prosody: { speed, volume: 0 },
  });
  return new Promise((resolve, reject) => {
    const req = request(FISH_URL, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/msgpack',
        'Content-Length': body.length,
        Authorization: `Bearer ${fishKey()}`,
        model: FISH_MODEL,
      },
      timeout: TIMEOUT_MS,
    }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        const data = Buffer.concat(chunks);
        if (res.statusCode === 200) return resolve(fixWavSizes(data));
        const detail = data.toString('utf8').slice(0, 200);
        reject(Object.assign(new Error(`Fish Audio error ${res.statusCode}: ${detail}`), { status: 502 }));
      });
    });
    req.on('timeout', () => req.destroy(new Error('timed out')));
    req.on('error', (err) => reject(Object.assign(new Error(`Could not reach Fish Audio: ${err.message}`), { status: 502 })));
    req.end(body);
  });
}

let cachedKey = null;
function voiceKey() {
  if (cachedKey) return cachedKey;
  const fromEnv = process.env.VOICE_SERVER_KEY;
  const file = ['voice-key.txt', 'voice-key'].map((name) => join(here, name)).find(existsSync);
  const key = (fromEnv || (file && readFileSync(file, 'utf8')) || '').trim();
  if (!key) {
    throw Object.assign(new Error('PC voice is not set up. Save the voice server API key in relay/voice-key.txt.'), { status: 503 });
  }
  cachedKey = key;
  return key;
}

function multipart(fields, audio) {
  const boundary = '----mangarelay' + randomBytes(12).toString('hex');
  const parts = [];
  for (const [name, value] of Object.entries(fields)) {
    if (value === undefined || value === null || value === '') continue;
    parts.push(Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="${name}"\r\n\r\n${value}\r\n`));
  }
  if (audio) {
    parts.push(Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="audio"; filename="voice.wav"\r\nContent-Type: audio/wav\r\n\r\n`));
    parts.push(audio, Buffer.from('\r\n'));
  }
  parts.push(Buffer.from(`--${boundary}--\r\n`));
  return { body: Buffer.concat(parts), type: `multipart/form-data; boundary=${boundary}` };
}

const isLocal = (host) => host === '127.0.0.1' || host === 'localhost' || host === '[::1]';

/** One call to the voice server's /api/v1/tts. Returns the WAV bytes. */
function callVoiceServer(fields, audio) {
  const { body, type } = multipart({ ...fields, format: 'wav' }, audio);
  const url = new URL('/api/v1/tts', VOICE_URL);
  return new Promise((resolve, reject) => {
    const req = request(url, {
      method: 'POST',
      headers: { 'Content-Type': type, 'Content-Length': body.length, Authorization: `Bearer ${voiceKey()}` },
      // A local voice server may use a self-signed certificate. Only trust it on this machine.
      rejectUnauthorized: !isLocal(url.hostname),
      timeout: TIMEOUT_MS,
    }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        const data = Buffer.concat(chunks);
        if (res.statusCode === 200) return resolve(data);
        const detail = data.toString('utf8').slice(0, 200);
        reject(Object.assign(new Error(`Voice server error ${res.statusCode}: ${detail}`), { status: 502 }));
      });
    });
    req.on('timeout', () => req.destroy(new Error('timed out')));
    req.on('error', (err) => reject(Object.assign(new Error(`Could not reach the voice server: ${err.message}`), { status: 502 })));
    req.end(body);
  });
}

/**
 * A reference clip, made with voice design the first time it is needed. Variant 0 is the
 * speaker type's own voice; variants 1 and up are the extra voices for named characters.
 */
async function castVoice(speaker, variant = 0, custom = '', soft = false, playful = false) {
  mkdirSync(CAST_DIR, { recursive: true });
  const name = (custom
    ? 'custom-' + custom.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 60)
    : speaker.replace(/\s+/g, '-') + (variant > 0 ? `-v${variant}` : '')) + (soft ? '-soft' : '') + (playful ? '-playful' : '');
  const base = join(CAST_DIR, name);
  const wav = `${base}.wav`;
  const txt = `${base}.txt`;
  if (playful && (!existsSync(wav) || !existsSync(txt))) {
    // Same voice, livelier delivery: clone the character's own clip reading a playful line.
    const plain = await castVoice(speaker, variant, custom, soft);
    const audio = await callVoiceServer(
      { text: PLAYFUL_REFERENCE_LINE, ref_text: plain.refText, params: JSON.stringify({ ...PLAYFUL_REFERENCE, denoise: true }) },
      plain.audio,
    );
    writeFileSync(wav, audio);
    writeFileSync(txt, PLAYFUL_REFERENCE_LINE);
    console.log(`[${new Date().toLocaleTimeString()}] Made a new ${name} voice in voices/`);
  } else if (!existsSync(wav) || !existsSync(txt)) {
    const line = soft ? SOFT_REFERENCE_LINE : REFERENCE_LINE;
    const audio = await callVoiceServer({
      text: line,
      instruct: custom || (variant > 0 ? VARIANTS[speaker][variant - 1] : SPEAKERS[speaker]),
      params: JSON.stringify({ ...(soft ? SOFT_REFERENCE : NATURAL), denoise: true }),
    });
    writeFileSync(wav, audio);
    writeFileSync(txt, line);
    console.log(`[${new Date().toLocaleTimeString()}] Made a new ${name} voice in voices/`);
  }
  const stat = statSync(wav);
  return { audio: readFileSync(wav), refText: readFileSync(txt, 'utf8').trim(), id: `${stat.size}-${stat.mtimeMs}` };
}

const castFileOf = (series) =>
  join(CAST_LIST_DIR, createHash('sha256').update(series).digest('hex').slice(0, 24) + '.json');

function readCastList(series) {
  try {
    const list = JSON.parse(readFileSync(castFileOf(series), 'utf8'));
    if (list && typeof list.characters === 'object') return list;
  } catch { /* new series, or a hand edit gone wrong: start again */ }
  return { series, characters: {} };
}

/**
 * The voice a named character keeps for the whole series. Picked the first time they speak,
 * from their speaker type's extra voices, using the one fewest characters have so far. The
 * speaker type is also fixed then, so a later misread of their age or gender changes nothing.
 */
const isValidEntry = (c) =>
  !!c && Object.hasOwn(VARIANTS, c.speaker) && Number.isInteger(c.variant) && c.variant >= 0 && c.variant <= VARIANTS[c.speaker].length;

function voiceFor(series, character, speaker) {
  const list = readCastList(series);
  const key = character.toLowerCase();
  const known = list.characters[key];
  // "sameAs" joins two labels for one person (the hero in a new outfit): they share one voice.
  const target = known && typeof known.sameAs === 'string' ? list.characters[known.sameAs] : null;
  if (isValidEntry(target)) return target;
  if (isValidEntry(known)) return known;
  const used = new Array(VARIANTS[speaker].length + 1).fill(0);
  for (const c of Object.values(list.characters)) if (c.speaker === speaker && used[c.variant] !== undefined) used[c.variant]++;
  let variant = 1;
  for (let v = 2; v < used.length; v++) if (used[v] < used[variant]) variant = v;
  const chosen = { name: character, speaker, variant };
  list.characters[key] = chosen;
  mkdirSync(CAST_LIST_DIR, { recursive: true });
  writeFileSync(castFileOf(series), JSON.stringify(list, null, 2));
  console.log(`[${new Date().toLocaleTimeString()}] ${character} gets the ${speaker} voice v${variant}`);
  return chosen;
}

export function validateSpeak(body) {
  const { text, tone, speaker, quality, speed, character, series, intensity, model, kind, sound } = body ?? {};
  const fail = (message) => { throw Object.assign(new Error(message), { status: 400 }); };
  if (model !== undefined && model !== '' && !MODELS.includes(model)) fail(`model must be one of ${MODELS.join(', ')}`);
  if (typeof text !== 'string' || !text.trim() || text.length > MAX_TEXT) fail(`text must be 1 to ${MAX_TEXT} characters`);
  if (speed !== undefined && (typeof speed !== 'number' || !Number.isFinite(speed))) fail('speed must be a number');
  const clean = (value, max) => (typeof value === 'string' ? value.replace(/\s+/g, ' ').trim().slice(0, max) : '');
  const name = clean(character, MAX_NAME);
  return {
    text: text.trim(),
    tone: Object.hasOwn(TONES, tone) ? tone : 'neutral',
    speaker: Object.hasOwn(SPEAKERS, speaker) ? speaker : 'unknown',
    quality: Object.hasOwn(QUALITIES, quality) ? quality : 'normal',
    speed: Math.min(2, Math.max(0.5, speed ?? 1)),
    // Older apps send none: 2 is the tone as it always sounded.
    intensity: [1, 2, 3].includes(intensity) ? intensity : 2,
    // Older apps send none: OmniVoice, as before.
    model: model || 'omnivoice',
    // What kind of text and which sound (newer apps): "thought", "system", "reaction"...
    kind: KINDS.includes(kind) ? kind : '',
    sound: SOUNDS.includes(sound) ? sound : '',
    character: ['unknown', 'narrator'].includes(name.toLowerCase()) ? '' : name,
    series: seriesName(series),
  };
}

// Numbered mirrors ("w112.", "www2.") are the same site. The app strips them too, but older
// builds did not, and a split here gives the series a second cast list.
function seriesName(raw) {
  return (typeof raw === 'string' ? raw.replace(/\s+/g, ' ').trim().slice(0, MAX_SERIES) : '').replace(/^(www\d*|w\d+)\./i, '');
}

const failWith = (status, message) => { throw Object.assign(new Error(message), { status }); };

/** What one cast entry sounds like, for the app's character list. */
function describeEntry(c, characters) {
  if (typeof c.sameAs === 'string' && isValidEntry(characters[c.sameAs])) {
    return `same voice as ${characters[c.sameAs].name}`;
  }
  const base = typeof c.voice === 'string' && c.voice.trim()
    ? c.voice.trim()
    : c.variant > 0 ? VARIANTS[c.speaker][c.variant - 1] : SPEAKERS[c.speaker];
  return base + (c.soft === true ? ', soft' : '') + (c.playful === true ? ', playful' : '');
}

/**
 * A series' characters and the voices they can have, for the app's character list.
 * The voice choices per speaker type are index 0 (plain) and then the extra voices.
 */
export function castListFor(rawSeries) {
  const series = seriesName(rawSeries);
  if (!series) failWith(400, 'series is required');
  const list = readCastList(series);
  const characters = Object.entries(list.characters)
    .filter(([, c]) => isValidEntry(c))
    .map(([key, c]) => ({
      key,
      name: c.name || key,
      speaker: c.speaker,
      variant: c.variant,
      voice: typeof c.voice === 'string' ? c.voice : '',
      soft: c.soft === true,
      playful: c.playful === true,
      sameAs: typeof c.sameAs === 'string' ? c.sameAs : '',
      sounds: describeEntry(c, list.characters),
    }));
  const voices = Object.fromEntries(Object.keys(VARIANTS).map((s) => [s, [SPEAKERS[s], ...VARIANTS[s]]]));
  return { series, characters, voices };
}

// Words OmniVoice's voice design understands, so a typo cannot design a strange voice.
const CUSTOM_VOICE = /^[a-z ,\-]{0,120}$/i;

/** Changes one character's voice from the app. Only the fields given change. */
export function updateCastEntry(body) {
  const { series: rawSeries, key, variant, voice, soft, playful, sameAs, speaker } = body ?? {};
  const series = seriesName(rawSeries);
  if (!series) failWith(400, 'series is required');
  const list = readCastList(series);
  const entry = typeof key === 'string' ? list.characters[key] : undefined;
  if (!isValidEntry(entry)) failWith(404, 'No such character in this series');
  // A new speaker type (the wrong gender or age was guessed): their voice comes from that type's list.
  if (speaker !== undefined && speaker !== entry.speaker) {
    if (typeof speaker !== 'string' || !Object.hasOwn(VARIANTS, speaker)) failWith(400, `speaker must be one of ${Object.keys(VARIANTS).join(', ')}`);
    entry.speaker = speaker;
    entry.variant = Math.min(entry.variant, VARIANTS[speaker].length);
  }
  if (variant !== undefined) {
    if (!Number.isInteger(variant) || variant < 0 || variant > VARIANTS[entry.speaker].length) failWith(400, 'variant is out of range');
    entry.variant = variant;
  }
  if (voice !== undefined) {
    if (typeof voice !== 'string' || !CUSTOM_VOICE.test(voice)) failWith(400, 'voice may only use letters, spaces, commas and hyphens');
    if (voice.trim()) entry.voice = voice.trim(); else delete entry.voice;
  }
  if (soft !== undefined) {
    if (typeof soft !== 'boolean') failWith(400, 'soft must be true or false');
    if (soft) entry.soft = true; else delete entry.soft;
  }
  if (playful !== undefined) {
    if (typeof playful !== 'boolean') failWith(400, 'playful must be true or false');
    if (playful) entry.playful = true; else delete entry.playful;
  }
  if (sameAs !== undefined) {
    if (typeof sameAs !== 'string') failWith(400, 'sameAs must be a character key');
    if (!sameAs) {
      delete entry.sameAs;
    } else {
      const target = list.characters[sameAs];
      if (sameAs === key || !isValidEntry(target)) failWith(400, 'sameAs must be another character in this series');
      // One step only: point at the character that has the voice, never at another link.
      if (typeof target.sameAs === 'string' && target.sameAs) failWith(400, `${target.name} already shares another character's voice`);
      entry.sameAs = sameAs;
      // Anyone who pointed at this character now points at the new one, so no chain forms.
      for (const c of Object.values(list.characters)) if (c.sameAs === key) c.sameAs = sameAs;
    }
  }
  mkdirSync(CAST_LIST_DIR, { recursive: true });
  const file = castFileOf(series);
  writeFileSync(`${file}.tmp`, JSON.stringify(list, null, 2));
  renameSync(`${file}.tmp`, file);
  console.log(`[${new Date().toLocaleTimeString()}] ${entry.name || key} now sounds like: ${describeEntry(entry, list.characters)}`);
  return castListFor(series).characters.find((c) => c.key === key);
}

/** Turns one bubble into a WAV clip, from the cache when it was made before. */
export async function speak(request) {
  const { text, tone, quality, speed, character, series, intensity = 2, model = 'omnivoice', kind = '', sound = '' } = request;
  let { speaker } = request;
  // Game windows have a voice of their own, so they stand apart from the narrator.
  if (kind === 'system') speaker = 'system';
  let variant = 0;
  let custom = '';
  let soft = false;
  let playful = false;
  if (character && series && speaker !== 'narrator' && speaker !== 'system') {
    const chosen = voiceFor(series, character, speaker);
    ({ speaker, variant } = chosen);
    // A hand-written description in the cast file, like "male, teenager, low pitch".
    if (typeof chosen.voice === 'string') custom = chosen.voice.trim().slice(0, 120);
    soft = chosen.soft === true;
    playful = chosen.playful === true;
  }
  let cast;
  try {
    cast = await castVoice(speaker, variant, custom, soft, playful);
  } catch (err) {
    // The game-window voice is made the first time it is needed; until the voice server can make it,
    // those lines use the narrator's.
    if (speaker !== 'system') throw err;
    console.log(`[${new Date().toLocaleTimeString()}] No game-window voice yet (${err.message}), using the narrator's`);
    speaker = 'narrator';
    cast = await castVoice(speaker);
  }
  let style = withIntensity(TONES[tone], tone === 'neutral' ? 2 : intensity);
  // A playful character acts every line a little livelier and quicker, even calm ones.
  if (playful) style = { ...style, class_temperature: Math.max(style.class_temperature, 0.7), guidance_scale: Math.min(style.guidance_scale, 1.8), speed: style.speed * 1.04 };
  // A soft character still sounds the feeling, but holds back: big outbursts are acted gently.
  if (soft) style = { ...style, class_temperature: Math.min(style.class_temperature, 0.6), guidance_scale: Math.max(style.guidance_scale, 1.9), speed: style.speed * 0.96 };
  const params = {
    ...(speaker === 'narrator' && tone === 'neutral' ? NARRATION : style),
    num_step: QUALITIES[quality],
    speed: Number(Math.min(1.5, Math.max(0.5, style.speed * speed)).toFixed(2)),
    denoise: true,
  };
  delete params.instruct;

  const key = createHash('sha256')
    .update(JSON.stringify({
      text, tone, speaker, quality, speed: params.speed, cast: cast.id,
      ...(intensity !== 2 && { intensity }),
      ...(model !== 'omnivoice' && { model, speaker, soft, playful }),
      // fishv: 8 drops clips made with a grunt cue ("[grunting] Tsk" could run on for 43 seconds).
      ...(model === 'fish' && { fish: FISH_MODEL, fishv: 8 }),
      ...(kind && { kind }),
      ...(sound && { sound }),
    }))
    .digest('hex');
  const cached = join(CACHE_DIR, `${key}.wav`);
  const voice = (custom ? `"${custom}"` : variant > 0 ? `${speaker} v${variant}` : speaker) + (soft ? ' soft' : '') + (playful ? ' playful' : '');
  if (existsSync(cached)) {
    const audio = readFileSync(cached);
    return { audio: model === 'fish' ? fixWavSizes(audio) : audio, cached: true, voice, model };
  }

  // The reference clips are always made with OmniVoice (voice design); the other models clone them.
  let fields;
  if (model === 'qwen3-tts') {
    const { expression, strength } = expressionFor(tone, intensity, speaker, soft, playful);
    fields = { model, text, ref_text: cast.refText, language: 'English', expression, expression_strength: strength };
  } else if (model === 'vibevoice') {
    fields = { model, text, ref_text: cast.refText, language: 'English' };
  } else {
    fields = { text, ref_text: cast.refText, instruct: style.instruct, params: JSON.stringify(params) };
  }
  const audio = model === 'fish'
    ? await callFishChecked(
      fishText(text, tone, intensity, speaker, soft, playful, kind, sound),
      text,
      // Fish takes 0.5 to 2. The tone's own pace (sad slower, excited quicker) carries over.
      Number(Math.min(2, Math.max(0.5, style.speed * speed)).toFixed(2)),
      cast,
    )
    : await callVoiceServer(fields, cast.audio);
  mkdirSync(CACHE_DIR, { recursive: true });
  writeFileSync(cached, audio);
  pruneCache();
  return { audio, cached: false, voice, model };
}

let writesSincePrune = 0;
function pruneCache() {
  if (++writesSincePrune < 100) return;
  writesSincePrune = 0;
  const files = readdirSync(CACHE_DIR).map((name) => {
    const path = join(CACHE_DIR, name);
    return { path, time: statSync(path).mtimeMs };
  });
  if (files.length <= CACHE_MAX_FILES) return;
  files.sort((a, b) => a.time - b.time);
  for (const file of files.slice(0, files.length - CACHE_KEEP_FILES)) {
    try { unlinkSync(file.path); } catch { /* already gone */ }
  }
}
