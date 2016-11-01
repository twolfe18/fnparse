#!/bin/bash

# This script specifies some CAG input, but in general this is how the
# ikbp.tac.IndexCommunications pipeline should work.

set -eu

OUTPUT_DIR=$1
COMM_GLOB=$2    # e.g. "$CAG/**/nyt_eng_2007*.tar.gz"
JAR=$3

CAG=/export/common/data/processed/concrete/concretely-annotated/gigaword/stanford

JAR_STABLE="$OUTPUT_DIR/fnparse.jar"
echo "copying jar to safe place:"
echo "    $JAR"
echo "==> $JAR_STABLE"
cp "$JAR" "$JAR_STABLE"

    #communicationArchives "$CAG/**/nyt_eng_2007*.tar.gz" \
#echo "counting strings... `date`"
#mkdir -p "$OUTPUT_DIR/raw"
#java -ea -Xmx32G -cp $JAR \
#  edu.jhu.hlt.ikbp.tac.IndexCommunications \
#    command tokenObs \
#    communicationArchives "$CAG/**/*.tar.gz" \
#    outputTokenObs "$OUTPUT_DIR/tokenObs.jser.gz" \
#    outputTokenObsLower "$OUTPUT_DIR/tokenObs.lower.jser.gz"
echo "copying ngram models into place... `date`"
cp /home/hltcoe/twolfe/character-sequence-counts/pruned/charCounts.lower-false.reverse-false.minCount3.jser.gz "$OUTPUT_DIR/tokenObs.jser.gz"
cp /home/hltcoe/twolfe/character-sequence-counts/pruned/charCounts.lower-true.reverse-false.minCount3.jser.gz "$OUTPUT_DIR/tokenObs.lower.jser.gz"

echo "extracting raw data... `date`"
mkdir -p "$OUTPUT_DIR/raw"
java -ea -Xmx40G -cp $JAR_STABLE \
  edu.jhu.hlt.ikbp.tac.IndexCommunications \
    command extractBasicData \
    communicationArchives "$COMM_GLOB" \
    outputDirectory "$OUTPUT_DIR/raw" \
    tokenObs "$OUTPUT_DIR/tokenObs.jser.gz" \
    tokenObsLower "$OUTPUT_DIR/tokenObs.lower.jser.gz"

echo "building document vectors... `date`"
mkdir -p "$OUTPUT_DIR/doc"
java -ea -cp $JAR_STABLE \
  edu.jhu.hlt.ikbp.tac.IndexCommunications \
    command buildDocVecs \
    numTerms 128 \
    input "$OUTPUT_DIR/raw/termDoc.txt.gz" \
    outputDocVecs "$OUTPUT_DIR/doc/docVecs.128.txt.gz" \
    outputIdf "$OUTPUT_DIR/doc/idf.txt"

echo "building ner feature indices... `date`"
mkdir -p "$OUTPUT_DIR/ner_feats"
time zcat "$OUTPUT_DIR/raw/nerFeatures.txt.gz" \
  | sort -n -k 1,2 -T /state/partition1 \
  | ./scripts/sem-diff/doc-indexing/convert-ner-feature-file-format.py \
    "$OUTPUT_DIR/ner_feats"

echo "compacting term-hash mapping... `date`"
zcat "$OUTPUT_DIR/raw/termHash.approx.txt.gz" \
  | sort -n -u \
  | gzip -c >"$OUTPUT_DIR/raw/termHash.sortu.txt.gz"

echo "done. `date`"

