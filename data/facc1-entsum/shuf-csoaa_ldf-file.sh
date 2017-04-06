#!/bin/bash

set -eu

INPUT=$1
OUTPUT=$2
SEED=$3

SCRIPTS=/home/hltcoe/twolfe/fnparse-build/fnparse/scripts

get_seeded_random()
{
	seed="$1"
	openssl enc -aes-256-ctr -pass pass:"$seed" -nosalt </dev/zero 2>/dev/null
}

$SCRIPTS/multiline-to-singleline $INPUT /dev/stdout '|||' \
	| shuf --random-source=<(get_seeded_random ${SEED}) \
	| $SCRIPTS/singleline-to-multiline /dev/stdin $OUTPUT '|||'

