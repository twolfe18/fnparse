#!/bin/bash

set -eu

OUTPUT_DIR=/export/projects/twolfe/cag-indexing

CAG=/export/common/data/processed/concrete/concretely-annotated/gigaword/stanford

JAR="`pwd`/target/fnparse-1.0.6-SNAPSHOT-jar-with-dependencies.jar"

#make jar

mkdir -p "$OUTPUT_DIR/nyt_eng_2007"
qsub -N "cag-index-sml" -l "h_rt=72:00:00,num_proc=1,mem_free=44G" \
  -j y -o "$OUTPUT_DIR/nyt_eng_2007" \
  ./scripts/sem-diff/doc-indexing/build-CAG-index.sh \
    "$OUTPUT_DIR/nyt_eng_2007" \
    "$CAG/**/nyt_eng_2007*.tar.gz" \
    "$JAR"

mkdir -p "$OUTPUT_DIR/apw_eng_2XXX"
qsub -N "cag-index-med" -l "h_rt=72:00:00,num_proc=1,mem_free=44G" \
  -j y -o "$OUTPUT_DIR/apw_eng_2XXX" \
  ./scripts/sem-diff/doc-indexing/build-CAG-index.sh \
    "$OUTPUT_DIR/apw_eng_2XXX" \
    "$CAG/**/apw_eng_2*.tar.gz" \
    "$JAR"

mkdir -p "$OUTPUT_DIR/full"
qsub -N "cag-index-full" -l "h_rt=72:00:00,num_proc=1,mem_free=44G" \
  -j y -o "$OUTPUT_DIR/full" \
  ./scripts/sem-diff/doc-indexing/build-CAG-index.sh \
    "$OUTPUT_DIR/full" \
    "$CAG/**/*.tar.gz" \
    "$JAR"

