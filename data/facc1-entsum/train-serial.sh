#!/bin/bash

set -eu

ENT_DIR_PARENT=$1	# e.g. /export/projects/twolfe/entity-summarization/clueweb-linked/entsum-data
OUTPUT_MODEL=$2
VW_OPTIONS=$3		# e.g. "-csoaa_ldf m -b 22 -q ::"
S=$4

echo "starting at `date` on `hostname`"

VW=/home/hltcoe/twolfe/vowpal_wabbit_binaries/vw-8.20170116
FNPARSE=/home/hltcoe/twolfe/fnparse-build/fnparse

get_seeded_random()
{
	seed="$1"
	openssl enc -aes-256-ctr -pass pass:"$seed" -nosalt </dev/zero 2>/dev/null
}

for SHUF in $(find $ENT_DIR_PARENT -name infobox-distsup.csoaa_ldf.shuf${S}.yx | shuf --random-source=<(get_seeded_random ${S})); do
	echo $SHUF
	if [[ -f $OUTPUT_MODEL ]]; then
		time $VW --ring_size 4096 -i $OUTPUT_MODEL -f $OUTPUT_MODEL -d $SHUF
	else
		time $VW $VW_OPTIONS --ring_size 4096 -f $OUTPUT_MODEL -d $SHUF
	fi
done

echo "done at `date`"

