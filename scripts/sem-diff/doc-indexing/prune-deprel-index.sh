#!/bin/bash

set -eu

# Takes the deprel output of IndexSituations and prepares it
# to be ingested into a StringIntUuidIndex.

# NOTE: This asks for a temp dir and does not clean up after itself!

INPUT_FILE=$1   # e.g. sit_feat/deprels.txt.gz
OUTPUT_FILE=$2  # input format of StringIntUuidIndex
OUTPUT_ALPH=$3  # stores hashed role/type/head strings
TEMP_DIR=$4
JAR=$5

if [[ ! -d $TEMP_DIR ]]; then
  echo "making temp dir: $TEMP_DIR"
  mkdir -p $TEMP_DIR
fi

echo "copying jar to safe place..."
JAR_STABLE="$TEMP_DIR/fnparse.jar"
cp $JAR $JAR_STABLE

# 1) prune relations by number of distinct argPairs
echo "pruning by relation... `date`"
zcat $INPUT_FILE \
  | LC_COLLATE=C sort -t '	' -k1 \
  >$TEMP_DIR/p1.txt
./scripts/sem-diff/doc-indexing/prune-deprel-index-helper.py \
  prune_rel \
  $TEMP_DIR/p1.txt \
  $TEMP_DIR/p2.txt \
  $TEMP_DIR/kept-deprels.txt

# 2) prune argPairs by number of distinct relations
echo "pruning by arguments... `date`"
LC_COLLATE=C sort -t '	' -k2 -k3 -k4 -k5 -k6 -k7 \
  <$TEMP_DIR/p2.txt \
  >$TEMP_DIR/p3.txt
./scripts/sem-diff/doc-indexing/prune-deprel-index-helper.py \
  prune_ent \
  $TEMP_DIR/p3.txt \
  $TEMP_DIR/p4.txt \
  $TEMP_DIR/kept-entPairs.txt

# 3) convert to (deprel, hash(role,ent), tok_uuid) format
echo "hashing strings... `date`"
java -ea -cp $JAR_STABLE \
  edu.jhu.hlt.ikbp.tac.IndexCommunications \
    command hashDeprels \
    input $TEMP_DIR/p4.txt \
    output $TEMP_DIR/p5.txt \
    argHash $OUTPUT_ALPH

# 4) sort the output so that StringIntUuidIndex can read in efficiently
echo "sorting output... `date`"
LC_COLLATE=C sort \
  <$TEMP_DIR/p5.txt \
  >$OUTPUT_FILE

echo "done `date`"

