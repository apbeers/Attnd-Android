#!/usr/bin/env python3
"""
Google Play Developer Publishing API (v3) uploads: an app bundle release, or the store listing.

Bundle release:
    play_publish.py app-dev.aab --track internal
    play_publish.py app-release.aab --track production --rollout 0.2 --notes-file store/en-US/release_notes.txt \
        --listing store/en-US                     # also the listing text, in the same edit as the release
        --mapping mapping.txt                     # R8 mapping for Play's crash deobfuscation (same edit)
        --native-symbols native-debug-symbols.zip # native debug symbols, if the build has any
        --draft                                   # a draft release: nothing goes to review or to users;
                                                  # a person rolls it out from Play Console. The listing text is
                                                  # left out (a listing change is sent for review on commit)

Store listing (text from store/en-US/*.txt, images from compose_screenshots.py):
    play_publish.py --listing store/en-US --check                      # validate the text only, offline
    play_publish.py --listing store/en-US                              # upload the text only
    play_publish.py --listing store/en-US --images build/store --images-only   # upload the images only
    play_publish.py --listing store/en-US --images build/store         # text and images
    … --not-for-review                                                 # commit, send from Play Console

Listing mode replaces the en-US title, short and full description, and/or every phone screenshot,
the feature graphic and the hi-res icon (one subdirectory per image type under --images:
phoneScreenshots/, featureGraphic/, icon/; files are uploaded in name order). Committing sends the
changes for review; they go live once approved.

Env: GOOGLE_PLAY_SERVICE_ACCOUNT_JSON (service account key JSON), PLAY_PACKAGE (default com.arcu.attnd)
Requires: PyJWT + cryptography (pip install "pyjwt[crypto]"); --check needs only the standard library.
"""

import argparse
import json
import os
import re
import struct
import sys
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

API = 'https://androidpublisher.googleapis.com/androidpublisher/v3/applications'
UPLOAD = 'https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications'
LANGUAGE = 'en-US'

# Play Console limits (characters).
LIMITS = {'title': 30, 'short_description': 80, 'full_description': 4000, 'release_notes': 500}

# Play's metadata policy: no performance, ranking, price or promotion claims. The title also may
# not use "new" or other promotional words, ALL CAPS (other than the brand) or repeated symbols.
# https://android-developers.googleblog.com/2021/04/updated-guidance-to-improve-your-app.html
CLAIMS = [
    r'best', r'#\s*1', r'no\.?\s*1', r'number\s+one', r'top[\s-]?(rated|ranked|app|free|paid)',
    r'free', r'sale', r'discount', r'cheap(est)?', r'cash\s*back', r'promo(tion)?', r'bonus',
    r'million\s+(downloads|users)', r'(app|game)\s+of\s+the\s+year', r"editor'?s\s+choice",
    r'download\s+now', r'install\s+now', r'limited\s+time',
]
TITLE_ONLY = [r'new', r'top', r'hot', r'popular', r'trending', r'download', r'install', r'play\s+now', r'try\s+now']
CAPS_ALLOWED = {'QR'}

# imageType → (count range, exact size or None, max bytes). Play: screenshots 320–3840 px per side,
# at most 2:1, ≤ 8 MB, 2–8 per type; feature graphic 1024×500; icon 512×512 ≤ 1 MB.
IMAGE_TYPES = {
    'phoneScreenshots': ((2, 8), None, 8 * 1024 * 1024),
    'featureGraphic': ((1, 1), (1024, 500), 15 * 1024 * 1024),
    'icon': ((1, 1), (512, 512), 1024 * 1024),
}


class PlayError(Exception):
    pass


def access_token(info):
    import jwt  # only the network paths need PyJWT

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
        raise PlayError(f'{method} {url.split("?")[0]} failed ({err.code}): '
                        f'{err.read().decode(errors="replace")[:1500]}') from None


# ── Text checks ─────────────────────────────────────────────────────────────

def read_text(path):
    """A listing text file without its trailing newline (Play counts every character)."""
    return Path(path).read_text(encoding='utf-8').strip()


def is_emoji(ch):
    code = ord(ch)
    return (unicodedata.category(ch) in ('So', 'Cs') or 0x1F000 <= code <= 0x1FAFF
            or code in (0x200D, 0xFE0F, 0x20E3))


def policy_problems(field, text):
    """Problems with one listing field; empty when it follows the limits and the metadata policy."""
    problems = []
    if not text:
        problems.append('is empty')
    if len(text) > LIMITS[field]:
        problems.append(f'is {len(text)} characters (max {LIMITS[field]})')
    emoji = sorted({ch for ch in text if is_emoji(ch)})
    if emoji:
        problems.append(f'contains emoji or symbols: {" ".join(emoji)}')
    # Release notes ("What's new") only get the length and emoji checks: "crash-free" is fine there.
    banned = [] if field == 'release_notes' else CLAIMS + (TITLE_ONLY if field == 'title' else [])
    for pattern in banned:
        match = re.search(rf'(?<![\w#]){pattern}(?!\w)', text, re.IGNORECASE)
        if match:
            problems.append(f'uses "{match.group(0)}" (no ranking, price or promotional claims)')
    if field == 'title':
        caps = [w for w in re.findall(r'[A-Za-z]{2,}', text) if w.isupper() and w not in CAPS_ALLOWED]
        if caps:
            problems.append(f'uses ALL CAPS: {", ".join(caps)}')
        if re.search(r'([^\w\s])\1', text) or re.search(r'[^\w\s]\s*[^\w\s]\s*[^\w\s]', text):
            problems.append('repeats special characters')
    if field == 'full_description':
        # Keyword stuffing: one word making up a large share of the text.
        words = [w.lower() for w in re.findall(r"[A-Za-z][A-Za-z']{3,}", text)]
        for word in set(words):
            count = words.count(word)
            if word != 'attnd' and count > 10 and count > len(words) * 0.05:
                problems.append(f'repeats "{word}" {count} times (keyword stuffing)')
    return problems


def check_fields(fields):
    problems = [f'{field}: {p}' for field, text in fields.items() for p in policy_problems(field, text)]
    if problems:
        sys.exit('Store listing text does not meet Play policy:\n  ' + '\n  '.join(problems))


def load_listing(store_dir):
    fields = {name: read_text(Path(store_dir) / f'{name}.txt')
              for name in ('title', 'short_description', 'full_description')}
    notes = Path(store_dir) / 'release_notes.txt'
    if notes.exists():
        fields['release_notes'] = read_text(notes)
    check_fields(fields)
    return fields


# ── Image checks ────────────────────────────────────────────────────────────

def png_info(path):
    """(width, height, has_alpha) from a PNG header, or exit if it isn't a PNG."""
    with open(path, 'rb') as f:
        head = f.read(26)
    if head[:8] != b'\x89PNG\r\n\x1a\n' or head[12:16] != b'IHDR':
        sys.exit(f'{path} is not a PNG')
    width, height = struct.unpack('>II', head[16:24])
    return width, height, head[25] in (4, 6)


def load_images(images_dir):
    images = {}
    problems = []
    for image_type, ((low, high), size, max_bytes) in IMAGE_TYPES.items():
        files = sorted((Path(images_dir) / image_type).glob('*.png'))
        if not low <= len(files) <= high:
            problems.append(f'{image_type}: {len(files)} PNGs (need {low}–{high})')
        for path in files:
            width, height, alpha = png_info(path)
            if size and (width, height) != size:
                problems.append(f'{path}: {width}×{height}, need {size[0]}×{size[1]}')
            if image_type == 'phoneScreenshots':
                if not (320 <= min(width, height) and max(width, height) <= 3840):
                    problems.append(f'{path}: each side must be 320–3840 px')
                if max(width, height) > 2 * min(width, height):
                    problems.append(f'{path}: aspect ratio over 2:1')
            if alpha and image_type != 'icon':
                problems.append(f'{path}: must be 24-bit PNG (no alpha)')
            if path.stat().st_size > max_bytes:
                problems.append(f'{path}: {path.stat().st_size} bytes (max {max_bytes})')
        images[image_type] = files
    if problems:
        sys.exit('Store listing images are not ready for Play:\n  ' + '\n  '.join(problems))
    return images


# ── Modes ───────────────────────────────────────────────────────────────────

def patch_listing_text(token, base, fields):
    # PATCH, not PUT: only these three fields change (a PUT would also clear the promo video).
    call(token, 'PATCH', f'{API}/{base}', body={
        'language': LANGUAGE, 'title': fields['title'],
        'shortDescription': fields['short_description'], 'fullDescription': fields['full_description']})
    print(f'Listing text: "{fields["title"]}"')


def publish_bundle(token, package, args, fields=None):
    # --notes (e.g. the RELEASE_NOTES repo variable) wins; otherwise the checked-in notes file.
    notes = args.notes.strip()
    if not notes and args.notes_file and Path(args.notes_file).exists():
        notes = read_text(args.notes_file)
    if notes:
        check_fields({'release_notes': notes})

    edit = call(token, 'POST', f'{API}/{package}/edits', body={})['id']
    with open(args.aab, 'rb') as f:
        bundle = call(token, 'POST', f'{UPLOAD}/{package}/edits/{edit}/bundles?uploadType=media',
                      raw=f.read(), content_type='application/octet-stream')
    version_code = bundle['versionCode']
    print(f'Uploaded bundle versionCode {version_code}')
    # edits.deobfuscationfiles.upload (media upload, application/octet-stream) for this version code:
    # `proguard` = the R8 mapping.txt, `nativeCode` = the native debug symbols zip.
    for kind, path in (('proguard', args.mapping), ('nativeCode', args.native_symbols)):
        if not path:
            continue
        with open(path, 'rb') as f:
            call(token, 'POST', f'{UPLOAD}/{package}/edits/{edit}/apks/{version_code}/deobfuscationFiles/{kind}'
                 '?uploadType=media', raw=f.read(), content_type='application/octet-stream')
        print(f'Uploaded {kind} deobfuscation file for {version_code} ({Path(path).stat().st_size} bytes)')

    if args.draft:  # prepared, not released: Play Console shows it under the track as a draft
        release = {'versionCodes': [str(version_code)], 'status': 'draft'}
    else:
        release = {'versionCodes': [str(version_code)],
                   'status': 'completed' if args.rollout >= 1 else 'inProgress'}
        if args.rollout < 1:
            release['userFraction'] = args.rollout
    if notes:
        release['releaseNotes'] = [{'language': LANGUAGE, 'text': notes}]
    call(token, 'PUT', f'{API}/{package}/edits/{edit}/tracks/{args.track}',
         body={'track': args.track, 'releases': [release]})
    if fields and args.draft:
        print('Draft: the listing text is left out (committing it would send it for review).')
        fields = None
    if fields:
        # Same edit as the release: the listing text goes out (and into review) together with it.
        patch_listing_text(token, f'{package}/edits/{edit}/listings/{LANGUAGE}', fields)
    # --not-for-review commits with changesNotSentForReview (the changes wait for "Send for review"). Play refuses
    # that parameter for apps whose changes are sent for review automatically (Nov 25, 2026, this app: 400 "must not
    # be set"), so a draft doesn't use it: a draft release is never reviewed or rolled out until a person starts it.
    hold = args.not_for_review
    call(token, 'POST', f'{API}/{package}/edits/{edit}:commit' + ('?changesNotSentForReview=true' if hold else ''))
    if args.draft:
        print(f'Draft release of {version_code} on the {args.track} track: nothing sent for review or rolled out. '
              'Open it in Play Console (Test and release → the track → Edit release) to review and roll it out.')
    else:
        print(f'Released {version_code} to the {args.track} track'
              + (f' at {args.rollout:.0%} rollout' if args.rollout < 1 else '')
              + (' (not sent for review)' if hold else ''))


def publish_listing(token, package, fields, images, not_for_review=False):
    """One edit: the listing text (unless fields is None) and the given image types."""
    edit = call(token, 'POST', f'{API}/{package}/edits', body={})['id']
    base = f'{package}/edits/{edit}/listings/{LANGUAGE}'
    if fields:
        patch_listing_text(token, base, fields)
    for image_type, files in images.items():
        deleted = call(token, 'DELETE', f'{API}/{base}/{image_type}').get('deleted', [])
        for path in files:
            call(token, 'POST', f'{UPLOAD}/{base}/{image_type}?uploadType=media',
                 raw=path.read_bytes(), content_type='image/png')
        print(f'{image_type}: replaced {len(deleted)} with {len(files)}')
    call(token, 'POST', f'{API}/{package}/edits/{edit}:validate')
    # Default commit behaviour (changesInReviewBehavior=CANCEL_IN_REVIEW_AND_SUBMIT): anything already
    # in review is withdrawn and resubmitted together with this listing. --not-for-review instead
    # leaves the changes in Play Console for a person to send (Play requires that after a rejection).
    query = '?changesNotSentForReview=true' if not_for_review else ''
    call(token, 'POST', f'{API}/{package}/edits/{edit}:commit{query}')
    if not_for_review:
        print('Committed without sending for review: send the changes from Play Console → Publishing overview.')
    else:
        print('Committed: the listing changes are sent for review and go live once approved.')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('aab', nargs='?', help='app bundle to release (bundle mode)')
    parser.add_argument('--track', help='internal, alpha, beta or production')
    parser.add_argument('--rollout', type=float, default=1.0, help='0 < fraction <= 1 (production staged rollout)')
    parser.add_argument('--notes', default='', help='release notes (overrides --notes-file)')
    parser.add_argument('--notes-file', help='release notes file used when --notes is empty, e.g. store/en-US/release_notes.txt')
    parser.add_argument('--mapping', metavar='FILE', help='bundle mode: R8 mapping.txt for this version code')
    parser.add_argument('--native-symbols', metavar='ZIP', help='bundle mode: native debug symbols zip')
    parser.add_argument('--listing', metavar='STORE_DIR',
                        help='store listing text, e.g. store/en-US: listing mode on its own, or with an .aab '
                             'the text is updated in the same edit as the release')
    parser.add_argument('--images', metavar='DIR', help='listing images: DIR/<imageType>/*.png')
    parser.add_argument('--images-only', action='store_true',
                        help='listing mode: upload the images and leave the listing text as it is')
    parser.add_argument('--check', action='store_true', help='listing mode: validate only, upload nothing')
    parser.add_argument('--not-for-review', action='store_true',
                        help="commit with changesNotSentForReview (send from Play Console)")
    parser.add_argument('--draft', action='store_true',
                        help='bundle mode: a draft release, committed without sending anything for review')
    args = parser.parse_args()

    bundle_mode = bool(args.aab)
    fields = load_listing(args.listing) if args.listing else None
    images = {}
    if bundle_mode:
        if not args.track:
            parser.error('bundle mode needs --track')
        if args.draft and args.rollout < 1:
            parser.error('--draft and --rollout: a draft has no rollout (set it when sending the release)')
        for path in (args.mapping, args.native_symbols):
            if path and not Path(path).is_file():
                parser.error(f'{path} does not exist')
    elif args.mapping or args.native_symbols:
        parser.error('--mapping / --native-symbols need an .aab (bundle mode)')
    elif args.listing:
        images = load_images(args.images) if args.images else {}
        if args.check:
            print(('Store listing text and images pass' if images else 'Store listing text passes') + ' the Play checks.')
            return
        if args.images_only:
            if not images:
                parser.error('--images-only needs --images')
            fields = None
    else:
        parser.error('give an .aab and --track (release) or --listing (store listing)')

    info = json.loads(os.environ['GOOGLE_PLAY_SERVICE_ACCOUNT_JSON'])
    package = os.environ.get('PLAY_PACKAGE', 'com.arcu.attnd')
    token = access_token(info)

    try:
        if bundle_mode:
            publish_bundle(token, package, args, fields)
            return
        # An edit fails to commit when another edit (e.g. a release from android.yml) was committed
        # meanwhile; the listing upload is idempotent, so start over with a fresh edit.
        for attempt in range(1, 4):
            try:
                publish_listing(token, package, fields, images, args.not_for_review)
                return
            except PlayError as err:
                if attempt == 3:
                    raise
                print(f'Attempt {attempt} failed, retrying with a new edit: {err}', file=sys.stderr)
                time.sleep(30 * attempt)
    except PlayError as err:
        sys.exit(str(err))


if __name__ == '__main__':
    main()
