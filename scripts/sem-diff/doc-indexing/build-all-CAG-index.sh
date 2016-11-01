#!/bin/bash

set -eu

OUTPUT_DIR=/export/projects/twolfe/cag-indexing

CAG=/export/common/data/processed/concrete/concretely-annotated/gigaword/stanford

#make jar

#mkdir -p "$OUTPUT_DIR/nyt_eng_2007"
#qsub -N "cag-index-sml" -l "h_rt=72:00:00,num_proc=1,mem_free=44G" \
#  ./scripts/sem-diff/doc-indexing/build-small-CAG-index.sh \
#    "$OUTPUT_DIR/nyt_eng_2007" \
#    "$CAG/**/nyt_eng_2007*.tar.gz" \
#    target/fnparse-1.0.6-SNAPSHOT-jar-with-dependencies.jar

mkdir -p "$OUTPUT_DIR/apw_eng_2XXX"
qsub -N "cag-index-med" -l "h_rt=72:00:00,num_proc=1,mem_free=44G" \
  ./scripts/sem-diff/doc-indexing/build-small-CAG-index.sh \
    "$OUTPUT_DIR/nyt_eng_2XXX" \
    "$CAG/**/apw_eng_2*.tar.gz" \
    target/fnparse-1.0.6-SNAPSHOT-jar-with-dependencies.jar

#mkdir -p "$OUTPUT_DIR/full"
#qsub -N "cag-index-full" -l "h_rt=72:00:00,num_proc=1,mem_free=44G" \
#  ./scripts/sem-diff/doc-indexing/build-small-CAG-index.sh \
#    "$OUTPUT_DIR/full" \
#    "$CAG/**/*.tar.gz" \
#    target/fnparse-1.0.6-SNAPSHOT-jar-with-dependencies.jar

