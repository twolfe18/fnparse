#!/bin/bash

set -eu

INPUT=$1
LINES=$2
OUTPUT=$3

head -n 1 $INPUT >$OUTPUT
tail -n+2 $INPUT | shuf -n $LINES >>$OUTPUT

