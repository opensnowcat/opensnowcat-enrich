#!/usr/bin/env python3

import http.server
import base64
import json
import sys

class AuthHandler(http.server.SimpleHTTPRequestHandler):
    """ Mock HTTP Server for API Lookup Enrichment Integration Test suite"""

    post_request_counter = 0

    def do_AUTHHEAD(self):
        self.send_response(401)
        self.send_header('WWW-Authenticate', 'Basic realm=\"API Lookup Enrichment test. User: snowplower, password: supersecret\"')
        self.send_header('Content-type', 'text/html')
        self.end_headers()

    def do_POST(self):
        ''' Present frontpage with user authentication. '''
        self.post_request_counter += 1
        self.protocol_version='HTTP/1.1'
        auth = self.headers.get('Authorization')
        if self.path.startswith("/guest"):
            self.send_response(200)
            response = self.generate_response("POST")
            self.send_header('Content-length', len(response))
            self.end_headers()
            self.write_body(response)
        elif auth is None:
            self.do_AUTHHEAD()
            self.write_body('no auth header received')
        elif auth == 'Basic ' + base64.b64encode(b'snowplower:supersecret').decode('UTF-8'):
            response = self.generate_response("POST", auth)
            self.send_response(200)
            self.send_header('Content-length', len(response))
            self.end_headers()
            self.write_body(response)
        else:
            self.do_AUTHHEAD()
            self.write_body(self.headers.getheader('Authorization'))
            self.write_body('not authenticated')

    def do_GET(self):
        if self.path.startswith("/guest"):
            self.send_response(200)
            response = self.generate_response("GET")
            self.end_headers()
            self.write_body(response)
        else:
            self.write_body('not authenticated')

    def generate_response(self, method, auth=None):
        if auth is not None:
            userpass = base64.decodebytes(auth[6:].encode('UTF-8')).decode('UTF-8')
            response = {
                "rootNull": None,
                "data": {
                    "firstKey": None,
                    "lookupArray": [
                        {
                            "path": self.path,
                            "auth_header": userpass,
                            "method": method,
                            "request": self.post_request_counter
                        }, {
                            "path": self.path,
                            "request": self.post_request_counter
                        }, {}
                    ]
                }
            }
        else:
            response = { "serviceName": "sp-api-request-enrichment" }
        return json.dumps(response)

    def write_body(self, body):
        self.wfile.write(body.encode('UTF-8'))

if __name__ == '__main__':
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8000
    print(f"Starting HTTP server for Common Fs2 on port {port}")
    httpd = http.server.HTTPServer(('', port), AuthHandler)
    httpd.serve_forever()
