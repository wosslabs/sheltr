import assert from 'node:assert/strict';
import { describe, it } from 'node:test';
import { hostAllowlistPolicy, rejectionMessage } from './policy.ts';

const policy = (...hosts: string[]) => hostAllowlistPolicy(hosts);
const ready = policy('www.ready.gov');

describe('host allowlist policy', () => {
  it('allows an https URI whose host is approved', () => assert.ok(ready.allows('https://www.ready.gov/x.pdf')));
  it('denies a host that is not approved', () => assert.ok(!ready.allows('https://evil.example/x.pdf')));
  it('denies an approved host name appearing only in the path', () => assert.ok(!ready.allows('https://evil.example/www.ready.gov')));
  it('denies everything with an empty list', () => assert.ok(!policy().allows('https://www.ready.gov/x.pdf')));
  it('matches the host exactly, not as a prefix or suffix', () => {
    assert.ok(!ready.allows('https://www.ready.gov.evil.example/x.pdf'));
    assert.ok(!ready.allows('https://evil.www.ready.gov/x.pdf'));
  });
  it('denies a trailing dot on the FQDN', () => assert.ok(!ready.allows('https://www.ready.gov./x.pdf')));
  it('ignores case in host and scheme', () => {
    assert.ok(policy('WWW.Ready.GOV').allows('https://www.READY.gov/x.pdf'));
    assert.ok(ready.allows('HTTPS://www.ready.gov/x'));
  });
  it('denies non-https schemes', () => {
    assert.ok(!ready.allows('http://www.ready.gov/x.pdf'));
    assert.ok(!ready.allows('file:///etc/passwd'));
  });
  it('allows the default https port written out and denies any other', () => {
    assert.ok(ready.allows('https://www.ready.gov:443/x.pdf'));
    assert.ok(!ready.allows('https://www.ready.gov:8443/x'));
    assert.ok(!ready.allows('https://www.ready.gov:80/x'));
  });
  it('denies a URI with no host', () => {
    assert.ok(!ready.allows('https:///x.pdf'));
    assert.ok(!ready.allows('https:x.pdf'));
  });
  it('denies userinfo in the authority', () => {
    assert.ok(!ready.allows('https://user@www.ready.gov/x.pdf'));
    assert.ok(!ready.allows('https://www.ready.gov@evil.example/x.pdf'));
  });
  it('denies invalid syntax and null', () => {
    assert.ok(!ready.allows('https://www.ready.gov/a b.pdf'));
    assert.ok(!ready.allows(null));
    assert.ok(!ready.allows(undefined));
  });
});

describe('rejectionMessage', () => {
  it('uses a fixed message for credentials and echoes nothing', () => {
    assert.equal(rejectionMessage('https://user:secret@www.ready.gov/x.pdf'), 'Source URI must not contain credentials.');
    assert.equal(rejectionMessage('https://www.ready.gov@evil.example/x.pdf'), 'Source URI must not contain credentials.');
  });
  it('names the host for other denials', () => assert.equal(rejectionMessage('https://evil.example/x.pdf'), 'Source host not allowed: evil.example'));
  it('truncates an overlong host to 255 characters', () => {
    const longHost = `${'a'.repeat(1000)}.example`;
    const message = rejectionMessage(`https://${longHost}/x.pdf`);
    assert.equal(message, `Source host not allowed: ${longHost.slice(0, 255)}`);
    assert.equal(message.length, 'Source host not allowed: '.length + 255);
  });
  it('handles missing and malformed URIs', () => {
    assert.equal(rejectionMessage(null), 'Source host not allowed: (none)');
    assert.equal(rejectionMessage('not a uri'), 'Source host not allowed: (none)');
  });
});
