import assert from 'node:assert/strict';
import {test} from 'node:test';
import {fallbackIdentity, parseAdbDevices, selectPhoneTransport} from '../scripts/install-latest-apks.mjs';

const base = {state:'device', responsive:true, emulator:false, hasRemote:true, hasReceiver:false};

test('ADB listing preserves Bonjour duplicate suffixes containing spaces', () => {
  const devices = parseAdbDevices(`List of devices attached
adb-PHONE-0ArFoP (2)._adb-tls-connect._tcp device product:m3 model:Phone transport_id:2
adb-PHONE-0ArFoP._adb-tls-connect._tcp device product:m3 model:Phone transport_id:1
OTHER unauthorized usb:1-1
`);
  assert.deepEqual(devices.map(device => [device.serial, device.state]), [
    ['adb-PHONE-0ArFoP (2)._adb-tls-connect._tcp', 'device'],
    ['adb-PHONE-0ArFoP._adb-tls-connect._tcp', 'device'],
    ['OTHER', 'unauthorized'],
  ]);
  assert.equal(fallbackIdentity(devices[0].serial), devices[1].serial);
});

test('duplicate transports with one hardware serial resolve to the stable phone transport', () => {
  const first = {...base, serial:'adb-PHONE (2)._adb-tls-connect._tcp', hardwareId:'PHONE'};
  const second = {...base, serial:'adb-PHONE._adb-tls-connect._tcp', hardwareId:'PHONE'};
  const result = selectPhoneTransport([first, second]);
  assert.equal(result.selected.serial, second.serial);
  assert.equal(result.transportCount, 2);
});

test('existing Remote install distinguishes the phone from other physical Android devices', () => {
  const phone = {...base, serial:'PHONE', hardwareId:'PHONE'};
  const blank = {...base, serial:'TABLET', hardwareId:'TABLET', hasRemote:false};
  const receiver = {...base, serial:'PROJECTOR', hardwareId:'PROJECTOR', hasRemote:false, hasReceiver:true};
  assert.equal(selectPhoneTransport([blank, receiver, phone]).selected.serial, 'PHONE');
});

test('selection refuses genuine ambiguity, emulators and receiver-only devices', () => {
  const first = {...base, serial:'ONE', hardwareId:'ONE', hasRemote:false};
  const second = {...base, serial:'TWO', hardwareId:'TWO', hasRemote:false};
  assert.throws(() => selectPhoneTransport([first, second]), /More than one physical phone/);
  assert.throws(() => selectPhoneTransport([
    {...base, serial:'emulator-5554', hardwareId:'EMU', emulator:true},
    {...base, serial:'PROJECTOR', hardwareId:'PROJECTOR', hasRemote:false, hasReceiver:true},
  ]), /No authorized/);
});

test('ANDROID_SERIAL remains an explicit exact override', () => {
  const first = {...base, serial:'ONE', hardwareId:'ONE', hasRemote:false};
  const second = {...base, serial:'TWO', hardwareId:'TWO', hasRemote:false};
  assert.equal(selectPhoneTransport([first, second], 'TWO').selected.serial, 'TWO');
  assert.throws(() => selectPhoneTransport([first], 'MISSING'), /does not name/);
});
