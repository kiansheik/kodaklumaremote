import test from 'node:test';
import assert from 'node:assert/strict';
import { isPrivateAddress, localDescriptionUrl, localDialUrl, targetDescription, transportActions, inspectTargetDescription } from '../scripts/discover-tv.mjs';

test('description URLs accept numeric private addresses and reject public/credential/redirect origins', () => {
  for (const address of ['192.168.1.3', '10.0.2.5', '172.16.1.7', '172.31.255.1', 'fd12::10', 'fc00::1']) assert.ok(isPrivateAddress(address));
  for (const address of ['127.0.0.1', '169.254.1.1', '172.32.1.1', '8.8.8.8', '::1', 'fe80::1', 'example.com']) assert.equal(isPrivateAddress(address), false);
  assert.ok(localDescriptionUrl('http://192.168.1.3:1400/desc.xml'));
  assert.ok(localDescriptionUrl('http://[fd12::10]/desc.xml'));
  for (const value of ['http://example.com/desc.xml', 'https://8.8.8.8/a', 'http://user:secret@192.168.1.3/a', 'file:///tmp/a', 'http://192.168.1.3/a#fragment']) assert.equal(localDescriptionUrl(value), null);
  assert.equal(localDescriptionUrl('http://192.168.1.4/scpd.xml', 'http://192.168.1.3/desc.xml'), null);
  assert.equal(localDescriptionUrl('/scpd.xml', 'http://192.168.1.3/desc.xml').pathname, '/scpd.xml');
});

test('requires the Philips target model and exposes only known service syntax', () => {
  const xml = '<root><device><manufacturer>Philips</manufacturer><modelName>43PFG6910/78</modelName><serialNumber>SECRET</serialNumber><serviceList><service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><SCPDURL>/transport.xml</SCPDURL></service><service><serviceType>http://private.example/SECRET</serviceType></service></serviceList></device></root>';
  assert.deepEqual(targetDescription(xml), { model: '43PFG6910/78', manufacturer: 'Philips', services: [{ type: 'urn:schemas-upnp-org:service:AVTransport:1', scpd: '/transport.xml' }] });
  assert.equal(targetDescription(xml.replace('43PFG6910/78', 'Other Philips')), null);
  assert.equal(targetDescription(xml.replace('43PFG6910/78', '43PFG6910/780')), null);
  assert.equal(targetDescription(xml.replace('Philips', 'TP Vision Europe B.V.')).manufacturer, 'TP Vision');
  assert.equal(targetDescription(xml.replace('Philips', 'TPV Technology Limited')).manufacturer, 'TPV');
  assert.equal(targetDescription(xml.replace('Philips', 'Other')), null);
  assert.throws(() => targetDescription('<!DOCTYPE root [<!ENTITY x SYSTEM "file:///secret">]>' + xml));
});

test('reads advertised transport actions without returning device-controlled text', () => {
  const xml = '<s:scpd><s:actionList><!-- <action><name>Stop</name></action> --><![CDATA[<action><name>Seek</name></action>]]><s:action><s:name>Play</s:name></s:action><s:action><s:name>SetAVTransportURI</s:name></s:action><s:action><s:name>http://private/SECRET</s:name></s:action></s:actionList></s:scpd>';
  assert.deepEqual(transportActions(xml), ['Play', 'SetAVTransportURI']);
  assert.throws(() => transportActions('x'.repeat(256 * 1024 + 1)));
});

test('DIAL bases allow another advertised port but require the descriptor numeric private peer', () => {
  const descriptor = 'http://192.168.1.3:49152/description.xml';
  for (const value of ['http://192.168.1.3:8008/apps/', 'https://192.168.1.3:9443/apps/', 'http://192.168.1.3/apps/']) {
    assert.equal(localDialUrl(value, descriptor).href, value);
  }
  assert.equal(localDialUrl('https://[fd12::10]:8008/apps/', 'http://[fd12::10]:49152/description.xml').port, '8008');
  for (const value of [undefined, '', '/apps/', '//192.168.1.3/apps/', 'http://192.168.1.4/apps/',
    'http://example.com/apps/', 'http://8.8.8.8/apps/', 'file:///apps/', 'http://127.0.0.1/apps/',
    'http://user:secret@192.168.1.3/apps/', 'http://@192.168.1.3/apps/', 'http://:@192.168.1.3/apps/',
    'http://192.168.1.3/apps/?secret=private', 'http://192.168.1.3/apps/?', 'http://192.168.1.3/apps/#',
    'http://192.168.1.3/apps/#private', 'http://192.168.1.3:0/apps/', 'http://192.168.1.3:65536/apps/',
    'http://192.168.1.3/a\nb', 'http://192.168.1.3/a\\b']) {
    assert.equal(localDialUrl(value, descriptor), null);
  }
  assert.equal(localDialUrl('http://192.168.1.3/apps/', 'https://example.com/description.xml'), null);
  assert.equal(localDialUrl('http://[fd12::11]/apps/', 'http://[fd12::10]/description.xml'), null);
});

const targetXml = '<root><device><manufacturer>Philips</manufacturer><modelName>43PFG6910/78</modelName><serialNumber>PRIVATE_DEVICE_ID</serialNumber><serviceList/></device></root>';

test('descriptor Application-URL survives empty XML services as a sanitized boolean only', async () => {
  const calls = [], location = 'http://192.168.1.3:49152/description.xml';
  const result = await inspectTargetDescription(location, undefined, async (url, options) => {
    calls.push(url.href);
    assert.equal(options.redirect, 'manual'); assert.equal(options.credentials, 'omit');
    return new Response(targetXml, { headers: { 'Content-Type': 'text/xml', 'ApPlIcAtIoN-UrL': 'https://192.168.1.3:8008/private-apps/' } });
  });
  assert.deepEqual(result, { descriptionErrors: 0, target: { model: '43PFG6910/78', manufacturer: 'Philips',
    serviceTypes: [], avTransportActions: [], avTransportDescriptionRead: false, dialAdvertised: true } });
  assert.deepEqual(calls, [location]); // No DIAL base, app state or launch request.
  for (const privateValue of ['192.168.1.3', '8008', 'private-apps', 'PRIVATE_DEVICE_ID', 'http']) {
    assert.equal(JSON.stringify(result).includes(privateValue), false);
  }
});

test('missing or rejected descriptor DIAL headers do not become capabilities', async () => {
  for (const header of [null, 'http://192.168.1.4/apps/', 'https://192.168.1.3/apps/?private=1']) {
    let calls = 0;
    const result = await inspectTargetDescription('http://192.168.1.3/description.xml', undefined, async () => {
      calls++; return new Response(targetXml, { headers: { 'content-type': 'application/xml', ...(header ? { 'application-url': header } : {}) } });
    });
    assert.equal(result.target.dialAdvertised, false); assert.equal(calls, 1);
  }
});

test('DIAL metadata preserves advertised same-origin AVTransport SCPD inspection', async () => {
  const location = 'http://192.168.1.3:49152/description.xml', calls = [];
  const xml = targetXml.replace('<serviceList/>', '<serviceList><service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><SCPDURL>/transport.xml</SCPDURL></service></serviceList>');
  const result = await inspectTargetDescription(location, undefined, async url => {
    calls.push(url.href);
    return new Response(url.href === location ? xml : '<scpd><actionList><action><name>Play</name></action><action><name>SetAVTransportURI</name></action></actionList></scpd>',
      { headers: { 'content-type': 'text/xml', ...(url.href === location ? { 'application-url': 'http://192.168.1.3:8008/apps/' } : {}) } });
  });
  assert.deepEqual(calls, [location, 'http://192.168.1.3:49152/transport.xml']);
  assert.equal(result.target.dialAdvertised, true); assert.equal(result.target.avTransportDescriptionRead, true);
  assert.deepEqual(result.target.avTransportActions, ['Play', 'SetAVTransportURI']);
});

test('a non-target descriptor with a valid DIAL header is excluded without app requests', async () => {
  let calls = 0;
  const result = await inspectTargetDescription('http://192.168.1.3/description.xml', undefined, async () => {
    calls++; return new Response(targetXml.replace('43PFG6910/78', 'Other TV'), {
      headers: { 'content-type': 'text/xml', 'application-url': 'http://192.168.1.3:8008/apps/' },
    });
  });
  assert.deepEqual(result, { target: null, descriptionErrors: 0 }); assert.equal(calls, 1);
});
