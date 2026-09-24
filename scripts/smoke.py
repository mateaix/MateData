#!/usr/bin/env python3
"""Verify the packaged application and durable state through HTTP, in isolated temporary data."""
import http.cookiejar
import json
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parent.parent
JAR = ROOT / 'matedata-server/target/matedata-server-0.1.0-SNAPSHOT.jar'
if not JAR.exists():
    raise SystemExit('Run ./scripts/build.sh first.')
with socket.socket() as reservation:
    reservation.bind(('127.0.0.1', 0))
    port = reservation.getsockname()[1]
base = f'http://127.0.0.1:{port}'
password = secrets.token_urlsafe(24)
model_key = secrets.token_urlsafe(24)


def client():
    return urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))


def request(opener, path, body=None, method=None, expected=200):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(base + path, data=data, method=method,
        headers={'Content-Type': 'application/json', 'X-MateData-Request': '1'})
    try:
        response = opener.open(req, timeout=15)
    except urllib.error.HTTPError as error:
        response = error
    payload = response.read()
    assert response.status == expected, f'{path}: expected {expected}, received {response.status}'
    content_type = response.headers.get('Content-Type', '').split(';')[0]
    return json.loads(payload) if content_type == 'application/json' or content_type.endswith('+json') else payload.decode()


def login(opener):
    result = request(opener, '/api/v1/auth/login', {'username': 'admin', 'password': password})
    assert result['role'] == 'ADMIN'


with tempfile.TemporaryDirectory(prefix='matedata-smoke-') as temp:
    data_dir = Path(temp)
    env = dict(os.environ, PORT=str(port), BIND_ADDRESS='127.0.0.1',
        MATEDATA_ADMIN_PASSWORD=password, MATEDATA_DATA_DIR=str(data_dir),
        MATEDATA_DATABASE_URL=f'jdbc:h2:file:{data_dir}/metadata;DB_CLOSE_ON_EXIT=FALSE',
        MATEDATA_DATABASE_PASSWORD='', MATEDATA_ENCRYPTION_KEY='', COOKIE_SECURE='false')
    for phase in ('initial', 'restart'):
        with (data_dir / f'{phase}.log').open('w') as log:
            process = subprocess.Popen([str(ROOT / 'scripts/run.sh')], cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
            try:
                deadline = time.monotonic() + 30
                while True:
                    if process.poll() is not None:
                        raise RuntimeError('Application exited before health check; isolated log: ' + str(data_dir / f'{phase}.log'))
                    try:
                        if request(client(), '/actuator/health')['status'] == 'UP':
                            break
                    except (urllib.error.URLError, ConnectionError, TimeoutError):
                        if time.monotonic() >= deadline:
                            raise TimeoutError('Application did not become healthy within 30 seconds')
                        time.sleep(0.2)
                anonymous = client()
                request(anonymous, '/api/v1/datasets', expected=401)
                page = request(anonymous, '/')
                asset = re.search(r'src="(/assets/[^\"]+\.js)"', page)
                assert asset and len(request(anonymous, asset.group(1))) > 1000
                authenticated = client()
                login(authenticated)
                if phase == 'initial':
                    run = request(authenticated, '/api/v1/queries', {'question': '各区域销售额', 'datasetId': 'sales', 'mode': 'demo'})
                    assert run['status'] == 'SUCCEEDED' and len(run['rows']) == 4
                    assert run['rows'][0] == {'region': '华东', 'revenue': '1449000.00'}
                    run_id = run['id']
                    report = request(authenticated, '/api/v1/evaluations/run', {'mode': 'demo'})
                    assert report['passed'] == report['total'] == 4
                    report_id = report['id']
                    model = request(authenticated, '/api/v1/settings/model', {'baseUrl': 'http://127.0.0.1:1/v1', 'model': 'smoke-only', 'apiKey': model_key, 'maxSteps': 3, 'timeoutSeconds': 5}, 'PUT')
                    assert model['configured'] and model_key not in json.dumps(model)
                    assert (data_dir / 'secret.key').exists()
                else:
                    restored = request(authenticated, '/api/v1/runs/' + run_id)
                    assert restored['rows'][0]['revenue'] == '1449000.00'
                    assert request(authenticated, '/api/v1/evaluations/reports/' + report_id)['passed'] == 4
                    settings = request(authenticated, '/api/v1/settings/model')
                    assert settings['configured'] and settings['model'] == 'smoke-only'
                    assert model_key not in json.dumps(settings)
                print(f'{phase}: bundled UI, auth, query/evaluation state and encrypted model configuration passed')
            finally:
                process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
    assert model_key.encode() not in (data_dir / 'metadata.mv.db').read_bytes()
print('Packaged application smoke passed; temporary processes and data removed.')
