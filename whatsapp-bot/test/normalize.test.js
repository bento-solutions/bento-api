import { test } from 'node:test';
import assert from 'node:assert/strict';
import { describeContent, receiptStatus, toInbound } from '../src/normalize.js';
import { e164ToPnJid, isIgnoredJid, pnJidToE164 } from '../src/jid.js';

test('text, captions and media placeholders', () => {
    assert.deepEqual(describeContent({ conversation: 'Salam' }), { type: 'text', body: 'Salam', quotedWamid: null });
    const ext = describeContent({ extendedTextMessage: { text: 'Oui', contextInfo: { stanzaId: 'Q1' } } });
    assert.equal(ext.body, 'Oui');
    assert.equal(ext.quotedWamid, 'Q1');
    const img = describeContent({ imageMessage: { caption: 'catalogue', mimetype: 'image/jpeg' } });
    assert.equal(img.body, 'catalogue');
    assert.equal(img.mediaType, 'image');
    assert.equal(describeContent({ audioMessage: { ptt: true } }).body, '[voice message]');
    assert.equal(describeContent({ documentMessage: { fileName: 'devis.pdf' } }).body, '[document] devis.pdf');
});

test('wrappers are unwrapped and non-conversation content is skipped', () => {
    assert.equal(describeContent({ ephemeralMessage: { message: { conversation: 'hidden' } } }).body, 'hidden');
    assert.equal(describeContent({ viewOnceMessageV2: { message: { imageMessage: {} } } }).body, '[image]');
    assert.equal(describeContent({ reactionMessage: { text: '👍' } }), null);
    assert.equal(describeContent({ protocolMessage: { type: 0 } }), null);
    assert.equal(describeContent(undefined), null);
});

test('toInbound keeps direction, alternate address and timestamp', () => {
    const inbound = toInbound({
        key: { id: 'W1', remoteJid: '123@lid', remoteJidAlt: '212612345678@s.whatsapp.net', fromMe: false },
        pushName: 'Karim',
        messageTimestamp: 1790300000,
        message: { conversation: 'prix ?' },
    }, 'live');
    assert.equal(inbound.direction, 'IN');
    assert.equal(inbound.altJid, '212612345678@s.whatsapp.net');
    assert.equal(inbound.pushName, 'Karim');
    assert.equal(inbound.occurredAt, new Date(1790300000 * 1000).toISOString());

    const own = toInbound({ key: { id: 'W2', remoteJid: '212612345678@s.whatsapp.net', fromMe: true },
        pushName: 'Owner', message: { conversation: 'ok' } }, 'offline');
    assert.equal(own.direction, 'OUT');
    assert.equal(own.pushName, null, 'the owner\'s own push name is never the contact\'s');
    assert.equal(toInbound({ key: { id: 'W3', remoteJid: 'x@s.whatsapp.net' }, message: { reactionMessage: {} } }, 'live'), null);
});

test('JID helpers never guess a country code', () => {
    assert.equal(pnJidToE164('212612345678@s.whatsapp.net'), '+212612345678');
    assert.equal(pnJidToE164('33612345678:12@s.whatsapp.net'), '+33612345678');
    assert.equal(pnJidToE164('123@lid'), null);
    assert.equal(e164ToPnJid('+212 612-345678'), '212612345678@s.whatsapp.net');
    assert.equal(e164ToPnJid('12'), null);
});

test('groups, broadcasts, status and newsletters are ignored', () => {
    for (const jid of ['1203630@g.us', 'status@broadcast', '123@broadcast', '120363@newsletter', null]) {
        assert.equal(isIgnoredJid(jid), true, jid);
    }
    assert.equal(isIgnoredJid('212612345678@s.whatsapp.net'), false);
    assert.equal(isIgnoredJid('123@lid'), false);
});

test('receipt statuses map to CRM statuses', () => {
    assert.equal(receiptStatus(2), 'SENT');
    assert.equal(receiptStatus(3), 'DELIVERED');
    assert.equal(receiptStatus(4), 'READ');
    assert.equal(receiptStatus(5), 'READ');
    assert.equal(receiptStatus(0), 'FAILED');
    assert.equal(receiptStatus(1), null);
});
