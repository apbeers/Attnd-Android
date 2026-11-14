#!/usr/bin/env python3
"""
Upload an Android App Bundle to Google Play with the Play Developer Publishing API (v3).

    play_publish.py app-release.aab --track internal
    play_publish.py app-release.aab --track production --rollout 0.2 --notes "Improvements"

Env: GOOGLE_PLAY_SERVICE_ACCOUNT_JSON (service account key JSON), PLAY_PACKAGE (default com.arcu.attnd)
Requires: PyJWT + cryptography (pip install "pyjwt[crypto]").
"""

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

import jwt

API = 'https://androidpublisher.googleapis.com/androidpublisher/v3/applications'
UPLOAD = 'https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications'


def access_token(info):
    now = int(time.time())
    assertion = jwt.encode({
        'iss': info['client_email'], 'scope': 'https://www.googleapis.com/auth/androidpublisher',
        'aud': info['token_uri'], 'iat': now, 'exp': now + 3600,
    }, info['private_key'], algorithm='RS256', headers={'kid': info.get('private_key_id')})
    body = urllib.parse.urlencode({'grant_type': 'urn:ietf:params:oauth:grant-type:jwt-bearer',
                                   'assertion': assertion}).encode()
    with urllib.request.urlopen(urllib.request.Request(info['token_uri'], data=body), timeout=30) as resp:
        return json.load(resp)['access_token']


def call(token, method, url, body=None, raw=None, content_type='application/json'):
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    req = urllib.request.Request(url, data=data, method=method, headers={
        'Authorization': f'Bearer {token}', 'Content-Type': content_type})
    try:
        with urllib.request.urlopen(req, timeout=600) as resp:
            text = resp.read()
            return json.loads(text) if text else {}
    except urllib.error.HTTPError as err:
        sys.exit(f'{method} {url.split("?")[0]} failed ({err.code}): {err.read().decode(errors="replace")[:1500]}')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('aab')
    parser.add_argument('--track', required=True, help='internal, alpha, beta or production')
    parser.add_argument('--rollout', type=float, default=1.0, help='0 < fraction <= 1 (production staged rollout)')
    parser.add_argument('--notes', default='')
    args = parser.parse_args()

    info = json.loads(os.environ['GOOGLE_PLAY_SERVICE_ACCOUNT_JSON'])
    package = os.environ.get('PLAY_PACKAGE', 'com.arcu.attnd')
    token = access_token(info)

    edit = call(token, 'POST', f'{API}/{package}/edits', body={})['id']
    with open(args.aab, 'rb') as f:
        bundle = call(token, 'POST', f'{UPLOAD}/{package}/edits/{edit}/bundles?uploadType=media',
                      raw=f.read(), content_type='application/octet-stream')
    version_code = bundle['versionCode']
    print(f'Uploaded bundle versionCode {version_code}')

    release = {'versionCodes': [str(version_code)],
               'status': 'completed' if args.rollout >= 1 else 'inProgress'}
    if args.rollout < 1:
        release['userFraction'] = args.rollout
    if args.notes:
        release['releaseNotes'] = [{'language': 'en-US', 'text': args.notes[:500]}]
    call(token, 'PUT', f'{API}/{package}/edits/{edit}/tracks/{args.track}',
         body={'track': args.track, 'releases': [release]})
    call(token, 'POST', f'{API}/{package}/edits/{edit}:commit')
    print(f'Released {version_code} to the {args.track} track'
          + (f' at {args.rollout:.0%} rollout' if args.rollout < 1 else ''))


if __name__ == '__main__':
    main()
