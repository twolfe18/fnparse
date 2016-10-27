#!/bin/bash

set -eu

OUTPUT_DIR=$1
JAR=$2

CAG=/export/common/data/processed/concrete/concretely-annotated/gigaword/stanford

echo "counting strings... `date`"
mkdir -p "$OUTPUT_DIR/raw"
java -ea -cp $JAR \
  edu.jhu.hlt.ikbp.tac.IndexCommunications \
    command tokenObs \
    communicationArchives "$CAG/**/nyt_eng_2007*.tar.gz" \
    outputTokenObs "$OUTPUT_DIR/tokenObs.jser.gz" \
    outputTokenObsLower "$OUTPUT_DIR/tokenObs.lower.jser.gz"

echo "extracting raw data... `date`"
mkdir -p "$OUTPUT_DIR/raw"
java -ea -cp $JAR \
  edu.jhu.hlt.ikbp.tac.IndexCommunications \
    command extractBasicData \
    communicationArchives "$CAG/**/nyt_eng_2007*.tar.gz" \
    outputDirectory "$OUTPUT_DIR/raw" \
    tokenObs "$OUTPUT_DIR/tokenObs.jser.gz" \
    tokenObsLower "$OUTPUT_DIR/tokenObs.lower.jser.gz"

echo "building document vectors... `date`"
mkdir -p "$OUTPUT_DIR/doc"
java -ea -cp $JAR \
  edu.jhu.hlt.ikbp.tac.IndexCommunications \
    command buildDocVecs \
    numTerms 128 \
    input "$OUTPUT_DIR/raw/termDoc.txt.gz" \
    outputDocVecs "$OUTPUT_DIR/doc/docVecs.128.txt.gz" \
    outputIdf "$OUTPUT_DIR/doc/idf.txt"

echo "done. `date`"

