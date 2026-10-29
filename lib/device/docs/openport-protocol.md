# OpenPort serial protocol

Reverse-engineered notes on the commands an OpenPort 2.0 accepts over its USB
serial device. Nothing here comes from vendor documentation: it was found by
probing an adapter with firmware 1.17.4877, first with no vehicle connected,
then on a 2015 Subaru Impreza. Meanings marked "likely" are inferences.

## Commands

Commands are ASCII lines terminated with `\r\n`. Replies are ASCII lines that
start with `ar`. The commands line up with J2534 PassThru functions, and error
codes are J2534 status codes.

Channel IDs are J2534 protocol IDs: 3 is ISO9141, 5 is CAN, and 6 is ISO15765.
Open channels and filters live in the adapter and survive closing and reopening
the serial port. Filter IDs count up across channels.

| Request | Reply | Meaning | Operation |
|---|---|---|---|
| `ati` | `ari main code version : 1.17.4877` | Firmware version | `:pass-thru-read-version` |
| `ata` | `aro` | OK; likely PassThruOpen | |
| `atz` | `aro` | OK; likely reset or PassThruClose | |
| `atr <pin>` | `arr <pin> <millivolts>` | Pin voltage; pin 16 is READ_VBATT | `:read-vbatt` |
| `ato<proto> <flags> <baud> [tag]` | `aro` | PassThruConnect | `:pass-thru-connect` |
| `atc<chan>` | `aro` | PassThruDisconnect; also `aro` if already closed | `:pass-thru-disconnect` |
| `atg<chan> <param> [tag]` | `arg<chan> <param> <value> <tag>` | GET_CONFIG | `:get-config` |
| `ats<chan> <param> <value>` | `aro` | SET_CONFIG | `:set-config` |
| `att<chan> <len> <flags>` + `len` raw bytes | `aro` | PassThruWriteMsgs | `:pass-thru-write-msgs` |
| `atf<chan> <type> <flags> <len> [tag]` + mask + pattern | `arf<chan> <filter-id> <tag>` | PassThruStartMsgFilter | `:pass-thru-start-msg-filter` |
| `atk<chan> <filter-id>` | `aro` | PassThruStopMsgFilter | `:pass-thru-stop-msg-filter` |
| any | `are <code>` | Error | |

The Operation column is the key in `openport-operations` in `openport.clj`,
which maps each J2534 operation to its confirmed command prefix.
`known-operation?` and `unsupported-operation` read it. Commands whose meaning
is only likely, such as `ata` and `atz`, and the ones under
[Partly understood](#partly-understood) are left out. When probing confirms
one, add it to the map and this table together.

Examples:

- `ato5 0 500000` opens CAN at 500 kbps. Opening it again returns `are 20`.
- `atg5 1` returns `arg5 1 500000 0` (DATA_RATE). `ats5 3 1` turns on
  LOOPBACK.
- `atf3 1 0 1` followed by bytes `00 00` adds a pass-all filter (type 1 is
  PASS, with a 1-byte mask `00` and a 1-byte pattern `00`) and returns
  `arf3 <id> 0`.

### Pin voltages

| Command | Reading |
|---|---|
| `atr 16` | OBD-II battery +12V: about 150-175 mV with no vehicle, 12591 mV on a vehicle with the engine off |
| `atr 17` | About 5533 mV; likely an internal supply |
| `atr 12` | 0 |

Other pins return `are 19`.

### Error codes

| Code | J2534 status |
|---|---|
| 1 | `ERR_NOT_SUPPORTED`, e.g. an unknown config parameter |
| 2 | `ERR_INVALID_CHANNEL_ID` |
| 3 | `ERR_INVALID_PROTOCOL_ID` |
| 7 | `ERR_FAILED`; also missing or malformed arguments |
| 9 | `ERR_TIMEOUT` |
| 10 | `ERR_INVALID_MSG` |
| 13 | `ERR_INVALID_MSG_ID` |
| 19 | `ERR_PIN_INVALID` |
| 20 | `ERR_CHANNEL_IN_USE` |
| 22 | `ERR_INVALID_FILTER_ID` |

### Gotchas

- `att` and `atf` read exactly the declared number of payload bytes. Extra
  bytes are parsed as the start of the next command, which then fails
  silently.
- With no payload, `att` waits about 0.5 seconds and returns `are 9`.
- With no vehicle, a correctly sized CAN `att` returns `are 9` after about
  1 second because no node on the bus acknowledges the frame.

## Received messages

Received messages are binary, not text lines, and arrive unprompted on the
serial stream between command replies:

```
"ar" <channel digit> <length u8> <status u8> <timestamp u32> <CAN ID u32> <data>
```

`length` counts the bytes after itself (1 + 4 + 4 + data). Integers are big
endian. The timestamp is the adapter's clock in microseconds.
`parse-adapter-stream` decodes this format.

For example, `ar5` `11` `00` `0A57C6EF` `00000370` `00 00 20 05 FC 07 00 00`
is an 8-byte CAN frame with ID `0x370`.

Status values seen:

| Status | Seen on | Meaning |
|---|---|---|
| `00` | CAN | Received frame, or a copy of a frame this adapter sent |
| `20` | CAN with LOOPBACK | ID 0, no data, right after the sent frame's copy; likely transmit done |
| `10` | ISO15765 | Sent ID, no data; likely transmit done |
| `80` | ISO15765 | Reply ID, no data; a multi-frame reply has started |
| `40` | ISO15765 with padding | Complete, reassembled message |

These are not standard J2534 RxStatus bit values, so treat their meanings as
observations.

On CAN, the adapter delivers a copy of each frame it sends whenever the
channel's filters pass that ID, even with LOOPBACK off. LOOPBACK adds the `20`
frame.

## On a vehicle

Tested on a 2015 Subaru with the ignition on and the engine off. The OBD port
carries live CAN traffic at 500 kbps, about 880 frames per second, so a CAN
channel with a pass-all filter (`atf5 1 0 4` + 4-byte mask `00000000` + 4-byte
pattern `00000000`) receives frames without sending anything.

OBD-II requests that worked:

- **Raw CAN:** `att5 12 0` + `000007DF 02 01 00 00 00 00 00 00` sends mode 01
  PID 00 to the functional address. The engine ECU replies on `7E8` with
  `06 41 00 BE 3F A8 13 00` about 1 ms later.
- **ISO15765:** add a flow-control filter with `atf6 3 64 4` + 4-byte mask
  `FFFFFFFF`, 4-byte pattern `000007E8`, and 4-byte flow-control ID
  `000007E0`. Then `att6 6 64` + `000007E0 01 00` gets `41 00 BE 3F A8 13`,
  and `att6 6 64` + `000007E0 09 02` gets the full 20-byte VIN reply as one
  reassembled message.

Flags 64 is `ISO15765_FRAME_PAD`. Without it, ISO15765 requests go out as
short frames and this ECU ignores them.

## Partly understood

- `atw<chan> <addr>` blocks for about 2.5 seconds, then returns `are 7` with
  no vehicle. That matches a 5-baud init (FIVE_BAUD_INIT) with no ECU
  answering.
- `atn<chan> <id>` returns `are 13`, so it is likely
  PassThruStopPeriodicMsg.
- `atp<chan>` and `aty<chan>` return `are 10`, so they expect a message. They
  are likely PassThruStartPeriodicMsg and FAST_INIT.
- `atx<chan> <id>` returns `are 7 <id>`. It echoes the ID like an ioctl, but
  its arguments are unknown.
- `atl<chan>` returns `aro`. It does not clear filters; its meaning is
  unknown.
- `atm<chan> ...` returns `are 7`; unknown.
- `atb`, `atd`, `ate`, `ath`, `atj`, `atq`, and `atu` never reply, with or
  without a channel.

## Not yet confirmed

- **Multi-frame ISO15765 transmit.** Every ISO15765 request sent so far fits
  in one CAN frame. Whether the adapter segments longer `att6` payloads, such
  as an SSM read of two or more addresses, has not been tried.
- **K-line receive.** With no vehicle, a K-line `att` with LOOPBACK on and a
  pass-all filter returned `aro` but produced no echo, probably because the
  K-line transceiver is powered from pin 16. Not yet retried on a vehicle.
- **`atv` with arguments.** It may set programming voltage on a pin, so do
  not send it until its arguments are understood.
