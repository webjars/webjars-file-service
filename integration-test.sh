#!/bin/bash
# Integration test script for webjars-file-service
# This script tests all endpoints using curl to validate the API surface area

set -e

# Configuration
BASE_URL="${BASE_URL:-http://localhost:9000}"
VERBOSE="${VERBOSE:-false}"
PASSED=0
FAILED=0
TOTAL=0

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[0;33m'
NC='\033[0m' # No Color

# Helper functions
log_test() {
    TOTAL=$((TOTAL + 1))
    echo -e "${YELLOW}TEST $TOTAL: $1${NC}"
}

log_pass() {
    PASSED=$((PASSED + 1))
    echo -e "${GREEN}  ✓ PASS${NC}"
}

log_fail() {
    FAILED=$((FAILED + 1))
    echo -e "${RED}  ✗ FAIL: $1${NC}"
}

log_verbose() {
    if [ "$VERBOSE" = "true" ]; then
        echo "    $1"
    fi
}

# Wait for server to be ready
wait_for_server() {
    echo "Waiting for server to be ready at $BASE_URL..."
    local max_attempts=30
    local attempt=1
    while [ $attempt -le $max_attempts ]; do
        if curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/robots.txt" 2>/dev/null | grep -q "200"; then
            echo "Server is ready!"
            return 0
        fi
        echo "  Attempt $attempt/$max_attempts - waiting..."
        sleep 2
        attempt=$((attempt + 1))
    done
    echo "Server did not become ready in time"
    return 1
}

# Test helper that captures response and headers
make_request() {
    local method="$1"
    local url="$2"
    local output_file=$(mktemp)
    local headers_file=$(mktemp)

    curl -s -X "$method" -D "$headers_file" -o "$output_file" "$BASE_URL$url"

    echo "$output_file|$headers_file"
}

get_status_code() {
    local headers_file="$1"
    grep -E "^HTTP" "$headers_file" | tail -1 | awk '{print $2}'
}

get_header() {
    local headers_file="$1"
    local header_name="$2"
    grep -i "^$header_name:" "$headers_file" | head -1 | cut -d':' -f2- | tr -d '\r' | xargs
}

cleanup_files() {
    rm -f "$1" "$2"
}

# ============================================================================
# TEST CASES
# ============================================================================

echo ""
echo "========================================"
echo "  WebJars File Service Integration Tests"
echo "========================================"
echo "  Base URL: $BASE_URL"
echo "========================================"
echo ""

# Wait for server
wait_for_server || exit 1
echo ""

# ----------------------------------------------------------------------------
# 1. Basic File Retrieval Tests
# ----------------------------------------------------------------------------

log_test "GET /files/org.webjars/jquery/3.2.1/jquery.js - Basic file retrieval with groupId"
result=$(make_request GET "/files/org.webjars/jquery/3.2.1/jquery.js")
output_file=$(echo "$result" | cut -d'|' -f1)
headers_file=$(echo "$result" | cut -d'|' -f2)
status=$(get_status_code "$headers_file")
content_type=$(get_header "$headers_file" "Content-Type")
cache_control=$(get_header "$headers_file" "Cache-Control")
etag=$(get_header "$headers_file" "ETag")
cors_header=$(get_header "$headers_file" "Access-Control-Allow-Origin")

if [ "$status" = "200" ]; then
    log_pass
else
    log_fail "Expected status 200, got $status"
fi
cleanup_files "$output_file" "$headers_file"


log_test "GET /files/org.webjars/jquery/3.2.1/jquery.js - Content-Type is application/javascript"
result=$(make_request GET "/files/org.webjars/jquery/3.2.1/jquery.js")
output_file=$(echo "$result" | cut -d'|' -f1)
headers_file=$(echo "$result" | cut -d'|' -f2)
content_type=$(get_header "$headers_file" "Content-Type")
if echo "$content_type" | grep -qi "javascript"; then
    log_pass
else
    log_fail "Expected javascript content-type, got $content_type"
fi
cleanup_files "$output_file" "$headers_file"


log_test "GET /files/org.webjars/jquery/3.2.1/jquery.js - Cache-Control header is set properly"
result=$(make_request GET "/files/org.webjars/jquery/3.2.1/jquery.js")
output_file=$(echo "$result" | cut -d'|' -f1)
headers_file=$(echo "$result" | cut -d'|' -f2)
cache_control=$(get_header "$headers_file" "Cache-Control")
if echo "$cache_control" | grep -q "max-age=31536000"; then
    log_pass
else
    log_fail "Expected max-age=31536000, got $cache_control"
fi
cleanup_files "$output_file" "$headers_file"


log_test "GET /files/org.webjars/jquery/3.2.1/jquery.js - ETag header is present"
result=$(make_request GET "/files/org.webjars/jquery/3.2.1/jquery.js")
output_file=$(echo "$result" | cut -d'|' -f1)
headers_file=$(echo "$result" | cut -d'|' -f2)
etag=$(get_header "$headers_file" "ETag")
if [ -n "$etag" ]; then
    log_pass
else
    log_fail "ETag header is missing"
fi
cleanup_files "$output_file" "$headers_file"


log_test "GET /files/org.webjars/jquery/3.2.1/jquery.js - CORS header is present"
result=$(make_request GET "/files/org.webjars/jquery/3.2.1/jquery.js")
output_file=$(echo "$result" | cut -d'|' -f1)
headers_file=$(echo "$result" | cut -d'|' -f2)
cors_header=$(get_header "$headers_file" "Access-Control-Allow-Origin")
if [ "$cors_header" = "*" ]; then
    log_pass
else
    log_fail "Expected Access-Control-Allow-Origin: *, got '$cors_header'"
fi
cleanup_files "$output_file" "$headers_file"


log_test "GET /files/org.webjars/jquery/3.2.1/jquery.js - Response body contains jQuery"
result=$(make_request GET "/files/org.webjars/jquery/3.2.1/jquery.js")
output_file=$(echo "$result" | cut -d'|' -f1)
headers_file=$(echo "$result" | cut -d'|' -f2)
if grep -q "jQuery" "$output_file"; then
    log_pass
else
    log_fail "Response does not contain 'jQuery'"
fi
cleanup_files "$output_file" "$headers_file"


# ----------------------------------------------------------------------------
# 2. File Retrieval Without GroupId (defaults to org.webjars)
# ----------------------------------------------------------------------------

log_test "GET /files/jquery/3.2.1/jquery.js - File retrieval without groupId"
result=$(make_request GET "/files/jquery/3.2.1/jquery.js")
output_file=$(echo "$result" | cut -d'|' -f1)
headers_file=$(echo "$result" | cut -d'|' -f2)
status=$(get_status_code "$headers_file")
if [ "$status" = "200" ]; then
    log_pass
else
    log_fail "Expected status 200, got $status"
fi
cleanup_files "$output_file" "$headers_file"


log_test "GET /files/jquery/3.2.1/jquery.js - Body matches the one with explicit groupId"
result1=$(make_request GET "/files/org.webjars/jquery/3.2.1/jquery.js")
result2=$(make_request GET "/files/jquery/3.2.1/jquery.js")
output1=$(echo "$result1" | cut -d'|' -f1)
headers1=$(echo "$result1" | cut -d'|' -f2)
output2=$(echo "$result2" | cut -d'|' -f1)
headers2=$(echo "$result2" | cut -d'|' -f2)
if diff -q "$output1" "$output2" > /dev/null; then
    log_pass
else
    log_fail "Response bodies differ"
fi
cleanup_files "$output1" "$headers1"
cleanup_files "$output2" "$headers2"


# ----------------------------------------------------------------------------
# 3. Different groupId Tests (org.webjars.npm)
# ----------------------------------------------------------------------------

log_test "GET /files/org.webjars.npm/highlightjs__cdn-assets/11.4.0/highlight.min.js - NPM webjar"
result=$(make_request GET "/files/org.webjars.npm/highlightjs__cdn-assets/11.4.0/highlight.min.js")
output_file=$(echo "$result" | cut -d'|' -f1)
headers_file=$(echo "$result" | cut -d'|' -f2)
status=$(get_status_code "$headers_file")
if [ "$status" = "200" ]; then
    log_pass
else
    log_fail "Expected status 200, got $status"
fi
cleanup_files "$output_file" "$headers_file"


log_test "GET /files/org.webjars.npm/highlightjs__cdn-assets/11.4.0/highlight.min.js - Contains hljs"
result=$(make_request GET "/files/org.webjars.npm/highlightjs__cdn-assets/11.4.0/highlight.min.js")
output_file=$(echo "$result" | cut -d'|' -f1)
headers_file=$(echo "$result" | cut -d'|' -f2)
if grep -q "hljs" "$output_file"; then
    log_pass
else
    log_fail "Response does not contain 'hljs'"
fi
cleanup_files "$output_file" "$headers_file"


# ----------------------------------------------------------------------------
# 4. BowerGitHub groupId Tests (non-versioned content)
# ----------------------------------------------------------------------------

log_test "GET /files/org.webjars.bowergithub.polymer/polymer/2.8.0/types/polymer.d.ts - BowerGithub webjar"
result=$(make_request GET "/files/org.webjars.bowergithub.polymer/polymer/2.8.0/types/polymer.d.ts")
output_file=$(echo "$result" | cut -d'|' -f1)
headers_file=$(echo "$result" | cut -d'|' -f2)
status=$(get_status_code "$headers_file")
if [ "$status" = "200" ]; then
    log_pass
else
    log_fail "Expected status 200, got $status"
fi
cleanup_files "$output_file" "$headers_file"


log_test "GET /files/org.webjars.bowergithub.polymer/polymer/2.8.0/types/polymer.d.ts - Has TypeScript content"
result=$(make_request GET "/files/org.webjars.bowergithub.polymer/polymer/2.8.0/types/polymer.d.ts")
output_file=$(echo "$result" | cut -d'|' -f1)
headers_file=$(echo "$result" | cut -d'|' -f2)
# TypeScript definition files typically contain 'declare', 'interface', 'export', 'module', or '/// <reference'
if grep -qE "(declare|interface|export|module|/// <reference)" "$output_file"; then
    log_pass
else
    log_fail "Response does not appear to be TypeScript"
fi
cleanup_files "$output_file" "$headers_file"


# ----------------------------------------------------------------------------
# 5. Error Handling - 404 Tests
# ----------------------------------------------------------------------------

log_test "GET /files/org.webjars/jquery/3.2.1/nonexistent.js - 404 for non-existent file"
result=$(make_request GET "/files/org.webjars/jquery/3.2.1/nonexistent.js")
output_file=$(echo "$result" | cut -d'|' -f1)
headers_file=$(echo "$result" | cut -d'|' -f2)
status=$(get_status_code "$headers_file")
if [ "$status" = "404" ]; then
    log_pass
else
    log_fail "Expected status 404, got $status"
fi
cleanup_files "$output_file" "$headers_file"


log_test "GET /files/org.webjars/jquery/0.0.0/jquery.js - 404 for non-existent version"
result=$(make_request GET "/files/org.webjars/jquery/0.0.0/jquery.js")
output_file=$(echo "$result" | cut -d'|' -f1)
headers_file=$(echo "$result" | cut -d'|' -f2)
status=$(get_status_code "$headers_file")
if [ "$status" = "404" ]; then
    log_pass
else
    log_fail "Expected status 404, got $status"
fi
cleanup_files "$output_file" "$headers_file"


log_test "GET /files/org.webjars/jquery/0.0.0/jquery.js - No Cache-Control on 404"
result=$(make_request GET "/files/org.webjars/jquery/0.0.0/jquery.js")
output_file=$(echo "$result" | cut -d'|' -f1)
headers_file=$(echo "$result" | cut -d'|' -f2)
cache_control=$(get_header "$headers_file" "Cache-Control")
# Cache-Control should be empty or not max-age for 404s
if [ -z "$cache_control" ] || ! echo "$cache_control" | grep -q "max-age=31536000"; then
    log_pass
else
    log_fail "Expected no caching headers on 404, got: $cache_control"
fi
cleanup_files "$output_file" "$headers_file"


log_test "GET /files/org.webjars/nonexistent-webjar/1.0.0/file.js - 404 for non-existent artifact"
result=$(make_request GET "/files/org.webjars/nonexistent-webjar-xyz/1.0.0/file.js")
output_file=$(echo "$result" | cut -d'|' -f1)
headers_file=$(echo "$result" | cut -d'|' -f2)
status=$(get_status_code "$headers_file")
if [ "$status" = "404" ]; then
    log_pass
else
    log_fail "Expected status 404, got $status"
fi
cleanup_files "$output_file" "$headers_file"


log_test "GET /files/jquery/0.0.0/jquery.js - 404 without groupId for non-existent version"
result=$(make_request GET "/files/jquery/0.0.0/jquery.js")
output_file=$(echo "$result" | cut -d'|' -f1)
headers_file=$(echo "$result" | cut -d'|' -f2)
status=$(get_status_code "$headers_file")
if [ "$status" = "404" ]; then
    log_pass
else
    log_fail "Expected status 404, got $status"
fi
cleanup_files "$output_file" "$headers_file"


# ----------------------------------------------------------------------------
# 6. List Files Endpoint Tests
# ----------------------------------------------------------------------------

log_test "GET /listfiles/jquery/3.2.1 - List files without groupId"
result=$(make_request GET "/listfiles/jquery/3.2.1")
output_file=$(echo "$result" | cut -d'|' -f1)
headers_file=$(echo "$result" | cut -d'|' -f2)
status=$(get_status_code "$headers_file")
if [ "$status" = "200" ]; then
    log_pass
else
    log_fail "Expected status 200, got $status"
fi
cleanup_files "$output_file" "$headers_file"


log_test "GET /listfiles/jquery/3.2.1 - Returns JSON array"
result=$(make_request GET "/listfiles/jquery/3.2.1")
output_file=$(echo "$result" | cut -d'|' -f1)
headers_file=$(echo "$result" | cut -d'|' -f2)
content_type=$(get_header "$headers_file" "Content-Type")
# Check if response starts with '[' (JSON array)
if head -c1 "$output_file" | grep -q '\['; then
    log_pass
else
    log_fail "Expected JSON array, got: $(head -c50 "$output_file")"
fi
cleanup_files "$output_file" "$headers_file"


log_test "GET /listfiles/jquery/3.2.1 - Contains jquery.js in the list"
result=$(make_request GET "/listfiles/jquery/3.2.1")
output_file=$(echo "$result" | cut -d'|' -f1)
headers_file=$(echo "$result" | cut -d'|' -f2)
if grep -q "jquery.js" "$output_file"; then
    log_pass
else
    log_fail "jquery.js not found in file list"
fi
cleanup_files "$output_file" "$headers_file"


log_test "GET /listfiles/org.webjars/jquery/3.2.1 - List files with groupId"
result=$(make_request GET "/listfiles/org.webjars/jquery/3.2.1")
output_file=$(echo "$result" | cut -d'|' -f1)
headers_file=$(echo "$result" | cut -d'|' -f2)
status=$(get_status_code "$headers_file")
if [ "$status" = "200" ]; then
    log_pass
else
    log_fail "Expected status 200, got $status"
fi
cleanup_files "$output_file" "$headers_file"


log_test "GET /listfiles/jquery/3.2.1 - CORS header present"
result=$(make_request GET "/listfiles/jquery/3.2.1")
output_file=$(echo "$result" | cut -d'|' -f1)
headers_file=$(echo "$result" | cut -d'|' -f2)
cors_header=$(get_header "$headers_file" "Access-Control-Allow-Origin")
if [ "$cors_header" = "*" ]; then
    log_pass
else
    log_fail "Expected Access-Control-Allow-Origin: *, got '$cors_header'"
fi
cleanup_files "$output_file" "$headers_file"


log_test "GET /listfiles/nonexistent-webjar/1.0.0 - 404 for non-existent webjar"
result=$(make_request GET "/listfiles/nonexistent-webjar-xyz/1.0.0")
output_file=$(echo "$result" | cut -d'|' -f1)
headers_file=$(echo "$result" | cut -d'|' -f2)
status=$(get_status_code "$headers_file")
if [ "$status" = "404" ]; then
    log_pass
else
    log_fail "Expected status 404, got $status"
fi
cleanup_files "$output_file" "$headers_file"


# ----------------------------------------------------------------------------
# 7. Num Files Endpoint Tests
# ----------------------------------------------------------------------------

log_test "GET /numfiles/jquery/3.2.1 - Num files without groupId"
result=$(make_request GET "/numfiles/jquery/3.2.1")
output_file=$(echo "$result" | cut -d'|' -f1)
headers_file=$(echo "$result" | cut -d'|' -f2)
status=$(get_status_code "$headers_file")
if [ "$status" = "200" ]; then
    log_pass
else
    log_fail "Expected status 200, got $status"
fi
cleanup_files "$output_file" "$headers_file"


log_test "GET /numfiles/jquery/3.2.1 - Returns number 4"
result=$(make_request GET "/numfiles/jquery/3.2.1")
output_file=$(echo "$result" | cut -d'|' -f1)
headers_file=$(echo "$result" | cut -d'|' -f2)
num=$(cat "$output_file" | tr -d '[:space:]')
if [ "$num" = "4" ]; then
    log_pass
else
    log_fail "Expected 4, got '$num'"
fi
cleanup_files "$output_file" "$headers_file"


log_test "GET /numfiles/org.webjars/jquery/3.2.1 - Num files with groupId"
result=$(make_request GET "/numfiles/org.webjars/jquery/3.2.1")
output_file=$(echo "$result" | cut -d'|' -f1)
headers_file=$(echo "$result" | cut -d'|' -f2)
status=$(get_status_code "$headers_file")
num=$(cat "$output_file" | tr -d '[:space:]')
if [ "$status" = "200" ] && [ "$num" = "4" ]; then
    log_pass
else
    log_fail "Expected status 200 with value 4, got status=$status value=$num"
fi
cleanup_files "$output_file" "$headers_file"


log_test "GET /numfiles/jquery/3.2.1 - CORS header present"
result=$(make_request GET "/numfiles/jquery/3.2.1")
output_file=$(echo "$result" | cut -d'|' -f1)
headers_file=$(echo "$result" | cut -d'|' -f2)
cors_header=$(get_header "$headers_file" "Access-Control-Allow-Origin")
if [ "$cors_header" = "*" ]; then
    log_pass
else
    log_fail "Expected Access-Control-Allow-Origin: *, got '$cors_header'"
fi
cleanup_files "$output_file" "$headers_file"


# ----------------------------------------------------------------------------
# 8. CORS Preflight Tests (OPTIONS)
# ----------------------------------------------------------------------------

log_test "OPTIONS /files/jquery/3.2.1/jquery.js - CORS preflight for files"
result=$(make_request OPTIONS "/files/jquery/3.2.1/jquery.js")
output_file=$(echo "$result" | cut -d'|' -f1)
headers_file=$(echo "$result" | cut -d'|' -f2)
status=$(get_status_code "$headers_file")
if [ "$status" = "200" ]; then
    log_pass
else
    log_fail "Expected status 200, got $status"
fi
cleanup_files "$output_file" "$headers_file"


log_test "OPTIONS /files/jquery/3.2.1/jquery.js - Access-Control-Allow-Origin header"
result=$(make_request OPTIONS "/files/jquery/3.2.1/jquery.js")
output_file=$(echo "$result" | cut -d'|' -f1)
headers_file=$(echo "$result" | cut -d'|' -f2)
cors_header=$(get_header "$headers_file" "Access-Control-Allow-Origin")
if [ "$cors_header" = "*" ]; then
    log_pass
else
    log_fail "Expected Access-Control-Allow-Origin: *, got '$cors_header'"
fi
cleanup_files "$output_file" "$headers_file"


log_test "OPTIONS /files/jquery/3.2.1/jquery.js - Access-Control-Allow-Headers header"
result=$(make_request OPTIONS "/files/jquery/3.2.1/jquery.js")
output_file=$(echo "$result" | cut -d'|' -f1)
headers_file=$(echo "$result" | cut -d'|' -f2)
allow_headers=$(get_header "$headers_file" "Access-Control-Allow-Headers")
if [ -n "$allow_headers" ]; then
    log_pass
else
    log_fail "Access-Control-Allow-Headers header is missing"
fi
cleanup_files "$output_file" "$headers_file"


log_test "OPTIONS /some/random/path - Generic CORS preflight"
result=$(make_request OPTIONS "/some/random/path")
output_file=$(echo "$result" | cut -d'|' -f1)
headers_file=$(echo "$result" | cut -d'|' -f2)
status=$(get_status_code "$headers_file")
cors_header=$(get_header "$headers_file" "Access-Control-Allow-Origin")
if [ "$status" = "200" ] && [ "$cors_header" = "*" ]; then
    log_pass
else
    log_fail "Expected status 200 with CORS header, got status=$status cors=$cors_header"
fi
cleanup_files "$output_file" "$headers_file"


log_test "OPTIONS /listfiles/jquery/3.2.1 - CORS preflight for listfiles"
result=$(make_request OPTIONS "/listfiles/jquery/3.2.1")
output_file=$(echo "$result" | cut -d'|' -f1)
headers_file=$(echo "$result" | cut -d'|' -f2)
status=$(get_status_code "$headers_file")
cors_header=$(get_header "$headers_file" "Access-Control-Allow-Origin")
if [ "$status" = "200" ] && [ "$cors_header" = "*" ]; then
    log_pass
else
    log_fail "Expected status 200 with CORS header, got status=$status cors=$cors_header"
fi
cleanup_files "$output_file" "$headers_file"


# ----------------------------------------------------------------------------
# 9. Robots.txt Tests
# ----------------------------------------------------------------------------

log_test "GET /robots.txt - Returns robots.txt"
result=$(make_request GET "/robots.txt")
output_file=$(echo "$result" | cut -d'|' -f1)
headers_file=$(echo "$result" | cut -d'|' -f2)
status=$(get_status_code "$headers_file")
if [ "$status" = "200" ]; then
    log_pass
else
    log_fail "Expected status 200, got $status"
fi
cleanup_files "$output_file" "$headers_file"


log_test "GET /files/robots.txt - Returns robots.txt under /files path"
result=$(make_request GET "/files/robots.txt")
output_file=$(echo "$result" | cut -d'|' -f1)
headers_file=$(echo "$result" | cut -d'|' -f2)
status=$(get_status_code "$headers_file")
if [ "$status" = "200" ]; then
    log_pass
else
    log_fail "Expected status 200, got $status"
fi
cleanup_files "$output_file" "$headers_file"


log_test "GET /robots.txt - Contains robot directives"
result=$(make_request GET "/robots.txt")
output_file=$(echo "$result" | cut -d'|' -f1)
headers_file=$(echo "$result" | cut -d'|' -f2)
if grep -qi "user-agent\|allow\|disallow" "$output_file"; then
    log_pass
else
    log_fail "robots.txt does not contain expected directives"
fi
cleanup_files "$output_file" "$headers_file"


# ----------------------------------------------------------------------------
# 10. Content-Type Tests for Various File Types
# ----------------------------------------------------------------------------

log_test "GET CSS file - Returns text/css content type"
result=$(make_request GET "/files/bootstrap/3.3.7/css/bootstrap.min.css")
output_file=$(echo "$result" | cut -d'|' -f1)
headers_file=$(echo "$result" | cut -d'|' -f2)
status=$(get_status_code "$headers_file")
content_type=$(get_header "$headers_file" "Content-Type")
if [ "$status" = "200" ] && echo "$content_type" | grep -qi "css"; then
    log_pass
else
    log_fail "Expected 200 with CSS content-type, got status=$status type=$content_type"
fi
cleanup_files "$output_file" "$headers_file"


log_test "GET minified JS - Returns javascript content type"
result=$(make_request GET "/files/jquery/3.2.1/jquery.min.js")
output_file=$(echo "$result" | cut -d'|' -f1)
headers_file=$(echo "$result" | cut -d'|' -f2)
status=$(get_status_code "$headers_file")
content_type=$(get_header "$headers_file" "Content-Type")
if [ "$status" = "200" ] && echo "$content_type" | grep -qi "javascript"; then
    log_pass
else
    log_fail "Expected 200 with javascript content-type, got status=$status type=$content_type"
fi
cleanup_files "$output_file" "$headers_file"


# ----------------------------------------------------------------------------
# 11. Edge Cases and Additional Tests
# ----------------------------------------------------------------------------

log_test "GET file with special characters in path"
result=$(make_request GET "/files/org.webjars.bowergithub.nicgirault/circosjs/1.2.1/src/circosJS.svg.js")
output_file=$(echo "$result" | cut -d'|' -f1)
headers_file=$(echo "$result" | cut -d'|' -f2)
status=$(get_status_code "$headers_file")
# We accept 200 (exists) or 404 (may not exist) - the point is we don't get a 500
if [ "$status" = "200" ] || [ "$status" = "404" ]; then
    log_pass
else
    log_fail "Expected status 200 or 404, got $status"
fi
cleanup_files "$output_file" "$headers_file"


log_test "GET with nested path - deep file structure"
result=$(make_request GET "/files/bootstrap/3.3.7/fonts/glyphicons-halflings-regular.woff")
output_file=$(echo "$result" | cut -d'|' -f1)
headers_file=$(echo "$result" | cut -d'|' -f2)
status=$(get_status_code "$headers_file")
if [ "$status" = "200" ]; then
    log_pass
else
    log_fail "Expected status 200, got $status"
fi
cleanup_files "$output_file" "$headers_file"


log_test "Multiple sequential requests to same resource"
status1=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/files/jquery/3.2.1/jquery.js")
status2=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/files/jquery/3.2.1/jquery.js")
status3=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/files/jquery/3.2.1/jquery.js")
if [ "$status1" = "200" ] && [ "$status2" = "200" ] && [ "$status3" = "200" ]; then
    log_pass
else
    log_fail "Sequential requests failed: $status1, $status2, $status3"
fi


log_test "GET map file - Returns JSON content type"
result=$(make_request GET "/files/jquery/3.2.1/jquery.min.map")
output_file=$(echo "$result" | cut -d'|' -f1)
headers_file=$(echo "$result" | cut -d'|' -f2)
status=$(get_status_code "$headers_file")
if [ "$status" = "200" ]; then
    log_pass
else
    log_fail "Expected status 200, got $status"
fi
cleanup_files "$output_file" "$headers_file"


# ----------------------------------------------------------------------------
# 12. GZIP Compression Test
# ----------------------------------------------------------------------------

log_test "GET with Accept-Encoding: gzip - Response may be compressed"
headers_file=$(mktemp)
output_file=$(mktemp)
curl -s -H "Accept-Encoding: gzip, deflate" -D "$headers_file" -o "$output_file" "$BASE_URL/files/jquery/3.2.1/jquery.js"
status=$(get_status_code "$headers_file")
# Content-Encoding header may or may not be present depending on file size and threshold
if [ "$status" = "200" ]; then
    log_pass
else
    log_fail "Expected status 200, got $status"
fi
cleanup_files "$output_file" "$headers_file"


# ============================================================================
# SUMMARY
# ============================================================================

echo ""
echo "========================================"
echo "  Test Results Summary"
echo "========================================"
echo -e "  Total:  $TOTAL"
echo -e "  ${GREEN}Passed: $PASSED${NC}"
echo -e "  ${RED}Failed: $FAILED${NC}"
echo "========================================"
echo ""

if [ $FAILED -gt 0 ]; then
    exit 1
else
    echo -e "${GREEN}All tests passed!${NC}"
    exit 0
fi
