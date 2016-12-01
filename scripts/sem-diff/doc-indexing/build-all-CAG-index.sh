#!/bin/bash

set -eu

#OUTPUT_DIR=/export/projects/twolfe/cag-indexing
#OUTPUT_DIR=/export/projects/twolfe/cag-indexing/2016-11-23
OUTPUT_DIR=/export/projects/twolfe/cag-indexing/2016-11-28
mkdir -p "$OUTPUT_DIR"

# Locally, see ~/code/fnparse/data/character-sequence-counts/charCounts.apw_eng_2000on.lower-*-false.minCount3.jser.gz
TOK_OBS=/home/hltcoe/twolfe/character-sequence-counts/pruned/charCounts.lower-false.reverse-false.minCount3.jser.gz
TOK_OBS_LC=/home/hltcoe/twolfe/character-sequence-counts/pruned/charCounts.lower-true.reverse-false.minCount3.jser.gz

#CAG=/export/common/data/processed/concrete/concretely-annotated/gigaword/stanford
CAG=/export/projects/fferraro/cag-4.6.10/processing/from-marcc/20161012-083257/gigaword-merged

JAR="`pwd`/target/fnparse-1.0.6-SNAPSHOT-jar-with-dependencies.jar"
#make jar
if [[ ! -f $JAR ]]; then
  echo "couldn't find jar: $JAR"
  exit 1
fi
echo "jar: $JAR"

mkdir -p "$OUTPUT_DIR/nyt_eng_2007"
qsub -cwd -l 'h_rt=72:00:00,num_proc=1,mem_free=44G' \
  -N "cag-index-sml" -j y -o "$OUTPUT_DIR/nyt_eng_2007" \
  ./scripts/sem-diff/doc-indexing/build-CAG-index.sh \
    "$OUTPUT_DIR/nyt_eng_2007" \
    "$CAG/**/nyt_eng_2007*.tar.gz" \
    "$TOK_OBS" \
    "$TOK_OBS_LC" \
    "$JAR"

mkdir -p "$OUTPUT_DIR/apw_eng_2XXX"
qsub -cwd -N "cag-index-med" -l "h_rt=72:00:00,num_proc=1,mem_free=44G" \
  -j y -o "$OUTPUT_DIR/apw_eng_2XXX" \
  ./scripts/sem-diff/doc-indexing/build-CAG-index.sh \
    "$OUTPUT_DIR/apw_eng_2XXX" \
    "$CAG/**/apw_eng_2*.tar.gz" \
    "$TOK_OBS" \
    "$TOK_OBS_LC" \
    "$JAR"

mkdir -p "$OUTPUT_DIR/full"
qsub -cwd -N "cag-index-full" -l "h_rt=72:00:00,num_proc=1,mem_free=44G" \
  -j y -o "$OUTPUT_DIR/full" \
  ./scripts/sem-diff/doc-indexing/build-CAG-index.sh \
    "$OUTPUT_DIR/full" \
    "$CAG/**/*.tar.gz" \
    "$TOK_OBS" \
    "$TOK_OBS_LC" \
    "$JAR"

