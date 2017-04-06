#!/bin/bash

ENT_DIR_PARENT=$1		# e.g. tokenized-sentences/train
K=$2					# e.g. 4 for 4 different random shuffles

SHUF=/home/hltcoe/twolfe/fnparse-build/fnparse/data/facc1-entsum/shuf-csoaa_ldf-file.sh

for F in $(find $ENT_DIR_PARENT -name infobox-distsup.csoaa_ldf.yx -size +1b); do
P=`dirname $F`
for S in `seq $K`; do
OUT=$P/infobox-distsup.csoaa_ldf.shuf${S}.yx
#echo "$SHUF $F $OUT $S"
qsub -cwd -N ds-shuf -j y -o $P -l "num_proc=1,mem_free=1G,h_rt=1:00:00" $SHUF $F $OUT $S
done
done

