import test from 'node:test';
import assert from 'node:assert/strict';
import { isPrivateAddress, localDescriptionUrl, targetDescription, transportActions } from '../scripts/discover-tv.mjs';

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
