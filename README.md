# OpenMCMelody

A Paper/Minecraft plugin that turns Standard MIDI Files into in-game note block music, playable to individual players or groups, with optional custom instrument sound packs and a browser-based control panel.

**Languages: [English](#english) | [한국어](#한국어)**

---

## English

### Features

- Plays `.mid` files through Minecraft's note block sounds, per-target (a player, several players, or the whole server).
- Accurate MIDI parsing: tempo changes, per-channel General MIDI program, CC7 (volume) / CC11 (expression) automation, and percussion (channel 10) are all honored.
- Shared **playlists** that loop through multiple songs, with the next song prefetched/parsed in advance so playback doesn't stall.
- Optional **custom soundpacks** — real instrument samples per GM instrument/octave, packaged into a Minecraft resource pack and pushed to players automatically.
- **`/midi soundpack fromsf2`** auto-builds a soundpack from a `.sf2`/`.dls` soundfont file (requires `ffmpeg`).
- A lightweight built-in **web control panel** (no extra dependencies) for browsing songs/playlists and controlling playback from a browser, secured by an in-game verification code.

### Requirements

- A Paper (or Paper-fork) server, API version `26.2`.
- A JVM with the `java.desktop` module available (needed for MIDI parsing).
- `ffmpeg` on the server's `PATH` (or configured via `soundfont.ffmpeg-path`) only if you plan to use `/midi soundpack fromsf2`.

### Installation

1. Build the plugin (see [Building from source](#building-from-source)) or grab a built jar, and drop it into your server's `plugins/` folder.
2. Start the server once to generate the `plugins/OpenMCMelody/` data folder (`config.yml`, `midi/`, `soundpacks/`, `soundfonts/`).
3. Drop `.mid` files into `plugins/OpenMCMelody/midi/`.
4. Edit `plugins/OpenMCMelody/config.yml` as needed (see below), then `/reload` or restart.

### Configuration (`config.yml`)

```yaml
web:
  enabled: true
  bind: '0.0.0.0'
  public-url: ''      # e.g. 'http://play.example.com:8080' - required for soundpack activation
  port: 8080

soundfont:
  ffmpeg-path: 'ffmpeg'
```

- `web.bind` is only the address the HTTP server listens on. `web.public-url` is what **players' clients** use to download resource packs from, and must be reachable by them (including the port) — resource pack activation silently fails without it.
- The web panel is plain HTTP (no TLS); put it behind a reverse proxy if you need HTTPS.

### Commands

All commands are under `/midi`:

| Command | Description | Permission |
|---|---|---|
| `/midi list` | List available MIDI files | `openmcmelody.midi.admin` |
| `/midi play <filename> [target]` | Play a MIDI file to yourself or a target | `openmcmelody.midi.admin` |
| `/midi stop [target]` | Stop playback | `openmcmelody.midi.admin` |
| `/midi status [player]` | Show current playback status | `openmcmelody.midi.status` (own), `.admin` (others) |
| `/midi pause [target]` / `/midi resume [target]` | Pause/resume playback | `openmcmelody.midi.status` (own), `.admin` (others) |
| `/midi playlist list\|show\|create\|delete\|add\|remove\|play` | Manage and play shared playlists | `openmcmelody.midi.playlist` |
| `/midi soundpack list\|build\|activate\|deactivate` | Manage custom soundpacks | `openmcmelody.midi.admin` |
| `/midi soundpack fromsf2 <file> <packname>` | Build a soundpack from a `.sf2`/`.dls` file | `openmcmelody.midi.admin` |
| `/midi verify <code>` | Confirm a web UI login | everyone |

Console must always specify an explicit `target`/`player` — it has no implicit self-target.

### Permissions

| Permission | Default | Description |
|---|---|---|
| `openmcmelody.midi.admin` | op | List, play and stop MIDI songs for any player, and check any player's status |
| `openmcmelody.midi.playlist` | true | Manage and play shared MIDI playlists |
| `openmcmelody.midi.status` | true | Check your own current MIDI playback status |

### Soundpacks

A soundpack lives at `plugins/OpenMCMelody/soundpacks/<name>/` with a `pack.yml`:

```yaml
name: My Cool Pack
description: Real instrument samples
sounds:
  acoustic_grand_piano_oct5: piano_oct5.ogg
  orchestral_harp_oct6: harp_oct6.ogg
  basedrum: kick.ogg
```

Sound files must already be Ogg Vorbis (`.ogg`). Any slot you don't define falls back to Minecraft's vanilla note block sound. Use `/midi soundpack build <name>` to validate and zip it, then `/midi soundpack activate <name>` to push it to online players (requires `web.public-url` to be set).

To generate one automatically from a soundfont, drop a `.sf2`/`.dls` file into `soundpacks/../soundfonts/` and run `/midi soundpack fromsf2 <file> <packname>` — this needs `ffmpeg` installed and reachable.

### Web control panel

If `web.enabled` is true (default), a control panel is served at `http://<bind>:<port>/`. Login works by claiming a Minecraft username in the browser, receiving a one-time code, and confirming it in-game with `/midi verify <code>` — so only the real owner of that username can log in.

### Building from source

```bash
./gradlew build       # builds the shaded plugin jar
./gradlew runServer   # builds and launches a local Paper test server with the plugin installed
```

There is no automated test suite in this repository.

---

## 한국어

### 소개

OpenMCMelody는 표준 MIDI 파일(`.mid`)을 마인크래프트의 노트블록 소리로 재생해주는 Paper 서버 플러그인입니다. 특정 플레이어, 여러 명, 또는 서버 전체를 대상으로 재생할 수 있으며, 커스텀 악기 사운드팩과 브라우저 기반 제어판도 지원합니다.

### 주요 기능

- `.mid` 파일을 노트블록 소리로 재생하며, 대상(플레이어 한 명, 여러 명, 또는 전체)을 지정할 수 있습니다.
- 정확한 MIDI 파싱: 템포 변화, 채널별 General MIDI 악기(Program), CC7(볼륨)/CC11(익스프레션) 오토메이션, 타악기(채널 10)까지 모두 반영됩니다.
- 여러 곡을 이어서 재생하는 공유 **재생목록(playlist)** 기능 — 다음 곡을 미리 파싱해두어 전환 시 끊김이 없습니다.
- 선택적인 **커스텀 사운드팩** — GM 악기/옥타브별 실제 악기 샘플을 리소스팩으로 패키징해 플레이어에게 자동으로 전달합니다.
- **`/midi soundpack fromsf2`** 명령으로 `.sf2`/`.dls` 사운드폰트 파일에서 사운드팩을 자동 생성할 수 있습니다 (`ffmpeg` 필요).
- 별도 의존성 없이 내장된 가벼운 **웹 제어판** — 브라우저에서 곡/재생목록을 탐색하고 재생을 제어할 수 있으며, 게임 내 인증 코드로 보호됩니다.

### 요구 사항

- Paper(또는 Paper 기반 포크) 서버, API 버전 `26.2`.
- MIDI 파싱을 위해 `java.desktop` 모듈이 포함된 JVM.
- `/midi soundpack fromsf2`를 사용할 경우에만 서버의 `PATH`에 `ffmpeg`가 있어야 합니다 (또는 `soundfont.ffmpeg-path`로 경로 지정).

### 설치

1. 플러그인을 빌드하거나([소스에서 빌드하기](#소스에서-빌드하기) 참고) 빌드된 jar 파일을 받아 서버의 `plugins/` 폴더에 넣습니다.
2. 서버를 한 번 실행해 `plugins/OpenMCMelody/` 데이터 폴더(`config.yml`, `midi/`, `soundpacks/`, `soundfonts/`)를 생성합니다.
3. `.mid` 파일을 `plugins/OpenMCMelody/midi/`에 넣습니다.
4. 필요에 따라 `plugins/OpenMCMelody/config.yml`을 수정한 뒤(아래 참고) `/reload`하거나 서버를 재시작합니다.

### 설정 (`config.yml`)

```yaml
web:
  enabled: true
  bind: '0.0.0.0'
  public-url: ''      # 예: 'http://play.example.com:8080' - 사운드팩 활성화에 필수
  port: 8080

soundfont:
  ffmpeg-path: 'ffmpeg'
```

- `web.bind`는 HTTP 서버가 수신 대기하는 주소일 뿐입니다. `web.public-url`은 **플레이어 클라이언트**가 리소스팩을 다운로드할 때 접속하는 주소이므로, 포트 번호까지 포함해 플레이어가 실제로 접근 가능한 값이어야 합니다 — 설정하지 않으면 사운드팩 활성화가 조용히 실패합니다.
- 웹 제어판은 TLS 없는 순수 HTTP입니다. HTTPS가 필요하면 리버스 프록시 뒤에 두세요.

### 명령어

모든 명령어는 `/midi` 하위에 있습니다:

| 명령어 | 설명 | 권한 |
|---|---|---|
| `/midi list` | 사용 가능한 MIDI 파일 목록 표시 | `openmcmelody.midi.admin` |
| `/midi play <파일명> [대상]` | 자신 또는 대상에게 MIDI 파일 재생 | `openmcmelody.midi.admin` |
| `/midi stop [대상]` | 재생 중지 | `openmcmelody.midi.admin` |
| `/midi status [플레이어]` | 현재 재생 상태 확인 | 본인: `openmcmelody.midi.status`, 타인: `.admin` |
| `/midi pause [대상]` / `/midi resume [대상]` | 재생 일시정지/재개 | 본인: `openmcmelody.midi.status`, 타인: `.admin` |
| `/midi playlist list\|show\|create\|delete\|add\|remove\|play` | 공유 재생목록 관리 및 재생 | `openmcmelody.midi.playlist` |
| `/midi soundpack list\|build\|activate\|deactivate` | 커스텀 사운드팩 관리 | `openmcmelody.midi.admin` |
| `/midi soundpack fromsf2 <파일> <팩이름>` | `.sf2`/`.dls` 파일로부터 사운드팩 생성 | `openmcmelody.midi.admin` |
| `/midi verify <코드>` | 웹 UI 로그인 확인 | 모든 사용자 |

콘솔에서는 자기 자신을 대상으로 할 수 없으므로 항상 `target`/`player`를 명시해야 합니다.

### 권한

| 권한 | 기본값 | 설명 |
|---|---|---|
| `openmcmelody.midi.admin` | op | 모든 플레이어에 대해 MIDI 곡 목록 조회, 재생, 중지, 상태 확인 |
| `openmcmelody.midi.playlist` | true | 공유 MIDI 재생목록 관리 및 재생 |
| `openmcmelody.midi.status` | true | 자신의 현재 MIDI 재생 상태 확인 |

### 사운드팩

사운드팩은 `plugins/OpenMCMelody/soundpacks/<이름>/` 폴더에 `pack.yml`과 함께 위치합니다:

```yaml
name: My Cool Pack
description: Real instrument samples
sounds:
  acoustic_grand_piano_oct5: piano_oct5.ogg
  orchestral_harp_oct6: harp_oct6.ogg
  basedrum: kick.ogg
```

사운드 파일은 반드시 Ogg Vorbis(`.ogg`) 형식이어야 합니다. 정의하지 않은 슬롯은 마인크래프트 기본(vanilla) 노트블록 소리로 대체됩니다. `/midi soundpack build <이름>`으로 검증 및 압축한 뒤, `/midi soundpack activate <이름>`으로 온라인 플레이어에게 전달합니다 (`web.public-url` 설정 필요).

사운드폰트로부터 자동 생성하려면 `.sf2`/`.dls` 파일을 `soundfonts/` 폴더에 넣고 `/midi soundpack fromsf2 <파일> <팩이름>`을 실행하세요 — 이 경우 `ffmpeg`가 설치되어 있어야 합니다.

### 웹 제어판

`web.enabled`가 true(기본값)이면 `http://<bind>:<port>/`에서 제어판이 제공됩니다. 로그인은 브라우저에서 마인크래프트 사용자명을 입력하고 발급받은 일회성 코드를 게임 내에서 `/midi verify <코드>`로 확인하는 방식으로 동작하여, 해당 사용자명의 실제 소유자만 로그인할 수 있습니다.

### 소스에서 빌드하기

```bash
./gradlew build       # 셰이드된 플러그인 jar 빌드
./gradlew runServer   # 빌드 후 플러그인이 설치된 로컬 Paper 테스트 서버 실행
```

이 저장소에는 자동화된 테스트가 포함되어 있지 않습니다.
