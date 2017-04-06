#!/bin/bash

# Launch one job for every entity to make infobox/relation prediction
set -eu
ENT_DIR_PARENT=$1
MODEL=$2

VW=/home/hltcoe/twolfe/vowpal_wabbit_binaries/vw-8.20170116
echo "VW=$VW"

MODEL_TAG=`echo $MODEL | tr '/' '\n' | tail -n 1 | perl -pe 's/\.vw$//'`

for FEATS in `find $ENT_DIR_PARENT -name infobox-pred.csoaa_ldf.x`; do
  ENTITY_DIR=`dirname $FEATS`
  PREDS=$ENTITY_DIR/infobox-pred.csoaa_ldf.${MODEL_TAG}.yhat
  #echo "$FEATS => $PREDS"
  #echo "MODEL_TAG=$MODEL_TAG"
  echo "$VW --ring_size 4096 -t -i $MODEL -r $PREDS -d $FEATS"
  qsub -cwd -N "infobox-pred.$MODEL_TAG" -b y -j y -o $ENTITY_DIR -l "num_proc=1,mem_free=1G,h_rt=8:00:00" \
    $VW --ring_size 4096 -t -i $MODEL -r $PREDS -d $FEATS
done


