#!/bin/bash

set -eu

#OUTPUT_DIR=/export/projects/twolfe/cag-indexing
#OUTPUT_DIR=/export/projects/twolfe/cag-indexing/2016-11-23
#OUTPUT_DIR=/export/projects/twolfe/cag-indexing/2016-11-28
#OUTPUT_DIR=/export/projects/twolfe/cag-indexing/2016-12-02
OUTPUT_DIR=/export/projects/twolfe/cag-indexing/2016-12-06
mkdir -p "$OUTPUT_DIR"

# Locally, see ~/code/fnparse/data/character-sequence-counts/charCounts.apw_eng_2000on.lower-*-false.minCount3.jser.gz
TOK_OBS=/home/hltcoe/twolfe/character-sequence-counts/charCounts.nyt_eng_2007.lower-false.reverse-false.minCount5.jser.gz
TOK_OBS_LC=/home/hltcoe/twolfe/character-sequence-counts/charCounts.nyt_eng_2007.lower-true.reverse-false.minCount5.jser.gz
#TOK_OBS=/home/hltcoe/twolfe/character-sequence-counts/pruned/charCounts.lower-false.reverse-false.minCount3.jser.gz
#TOK_OBS_LC=/home/hltcoe/twolfe/character-sequence-counts/pruned/charCounts.lower-true.reverse-false.minCount3.jser.gz

#CAG=/export/common/data/processed/concrete/concretely-annotated/gigaword/stanford
CAG=/export/projects/fferraro/cag-4.6.10/processing/from-marcc/20161012-083257/gigaword-merged/tgz
#DATA_PROVIDER="disk:$CAG"
#DATA_PROVIDER="scion"

THIS_SCRIPT_DIR=`dirname $(readlink -f $0)`
SCRIPTS=`readlink -f $THIS_SCRIPT_DIR/../..`
echo "SCRIPTS=$SCRIPTS"
if [[ ! -d $SCRIPTS ]]; then
  echo "can't find SCRIPTS=$SCRIPTS"
  exit 2
fi

JAR="`pwd`/target/fnparse-1.0.6-SNAPSHOT-jar-with-dependencies.jar"
if [[ ! -f $JAR ]]; then
  echo "couldn't find jar: $JAR"
  exit 1
fi
echo "jar: $JAR"

#for DATA_PROVIDER in "scion" "disk:$CAG"; do
for DATA_PROVIDER in "simpleAccumulo:twolfe-cag1"; do

#SCION_STR="disk"
SCION_STR=`echo "$DATA_PROVIDER" | tr ':' '_'`
if [[ $DATA_PROVIDER == "scion" ]]; then
SCION_STR="scion"
continue
fi

echo "SS=$SCION_STR"

#OD="$OUTPUT_DIR/nyt_eng_2007-$SCION_STR"
#mkdir -p "$OD"
#qsub -cwd -N "cag-index-sml-$SCION_STR" -l 'h_rt=72:00:00,num_proc=1,mem_free=44G' -j y -o "$OD" \
#  ./scripts/sem-diff/doc-indexing/build-CAG-index.sh \
#    "$OD" \
#    CAG_SMALL \
#    "$DATA_PROVIDER" \
#    "$TOK_OBS" \
#    "$TOK_OBS_LC" \
#    "$SCRIPTS" \
#    "$JAR"
#
#OD="$OUTPUT_DIR/apw_eng_2XXX-$SCION_STR"
#mkdir -p "$OD"
#qsub -cwd -N "cag-index-med-$SCION_STR" -l "h_rt=72:00:00,num_proc=1,mem_free=44G" -j y -o "$OD" \
#  ./scripts/sem-diff/doc-indexing/build-CAG-index.sh \
#    "$OD" \
#    CAG_MEDIUM \
#    "$DATA_PROVIDER" \
#    "$TOK_OBS" \
#    "$TOK_OBS_LC" \
#    "$SCRIPTS" \
#    "$JAR"

OD="$OUTPUT_DIR/full-$SCION_STR"
mkdir -p "$OD"
#qsub -cwd -N "cag-index-full-$SCION_STR" -l "h_rt=72:00:00,num_proc=1,mem_free=44G" -j y -o "$OD" \
  ./scripts/sem-diff/doc-indexing/build-CAG-index.sh \
    "$OD" \
    CAG_FULL \
    "$DATA_PROVIDER" \
    "$TOK_OBS" \
    "$TOK_OBS_LC" \
    "$SCRIPTS" \
    "$JAR"

done

