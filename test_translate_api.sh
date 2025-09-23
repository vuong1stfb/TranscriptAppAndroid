#!/bin/bash

# Test the Google Translate API directly using curl to verify our endpoint works
echo "Testing Google Translate API endpoint..."

# Test case 1: Simple English text
echo -e "\n=== Test 1: Simple English text ==="
TEST_TEXT_1="Hello, how are you today?"
ENCODED_TEXT_1=$(echo "$TEST_TEXT_1" | sed 's/ /%20/g' | sed 's/,/%2C/g' | sed 's/?/%3F/g')
URL_1="https://translate.googleapis.com/translate_a/single?client=gtx&dt=t&dj=1&sl=auto&tl=vi&q=$ENCODED_TEXT_1"

echo "Original text: $TEST_TEXT_1"
echo "URL: $URL_1"
echo "Response:"
curl -s "$URL_1" | python3 -m json.tool 2>/dev/null || curl -s "$URL_1"

# Test case 2: The example from problem statement
echo -e "\n=== Test 2: Example from problem statement ==="
TEST_TEXT_2="You're receiving notifications because you modified the open/close state."
ENCODED_TEXT_2=$(python3 -c "import urllib.parse; print(urllib.parse.quote('$TEST_TEXT_2'))")
URL_2="https://translate.googleapis.com/translate_a/single?client=gtx&dt=t&dj=1&sl=auto&tl=vi&q=$ENCODED_TEXT_2"

echo "Original text: $TEST_TEXT_2"
echo "URL: $URL_2"
echo "Response:"
curl -s "$URL_2" | python3 -m json.tool 2>/dev/null || curl -s "$URL_2"

# Test case 3: Technical text
echo -e "\n=== Test 3: Technical text ==="
TEST_TEXT_3="The transcription process has completed successfully."
ENCODED_TEXT_3=$(python3 -c "import urllib.parse; print(urllib.parse.quote('$TEST_TEXT_3'))")
URL_3="https://translate.googleapis.com/translate_a/single?client=gtx&dt=t&dj=1&sl=auto&tl=vi&q=$ENCODED_TEXT_3"

echo "Original text: $TEST_TEXT_3"
echo "URL: $URL_3"
echo "Response:"
curl -s "$URL_3" | python3 -m json.tool 2>/dev/null || curl -s "$URL_3"

echo -e "\n=== Test completed ==="