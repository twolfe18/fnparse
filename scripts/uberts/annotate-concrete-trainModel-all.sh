#!/bin/bash

# Train a FN and PB model and put them in place.

set -eu

make jar
JAR=target/fnparse-1.0.6-SNAPSHOT-jar-with-dependencies.jar

FNPARSE_DATA=/export/projects/twolfe/fnparse-data

MODEL_OUT=/export/projects/twolfe/fnparse-models/concretely-annotated-gigaword/2016-10-11
mkdir -p $MODEL_OUT

rm -rf $MODEL_OUT/fn
qsub -N "cag-fn-fnparse" -l "mem_free=6G,num_proc=1,h_rt=40:00:00" \
  -cwd -j y -o $MODEL_OUT/trainLog-fn.txt \
  ./scripts/uberts/annotate-concrete-trainModel-fn.sh $MODEL_OUT/fn $FNPARSE_DATA $JAR

rm -rf $MODEL_OUT/pb
qsub -N "cag-pb-fnparse" -l "mem_free=6G,num_proc=1,h_rt=80:00:00" \
  -cwd -j y -o $MODEL_OUT/trainLog-pb.txt \
  ./scripts/uberts/annotate-concrete-trainModel-pb.sh $MODEL_OUT/pb $FNPARSE_DATA $JAR

sleep 4

qinfo

