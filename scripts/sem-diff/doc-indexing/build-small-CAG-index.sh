#!/bin/bash

set -eu

OUTPUT_DIR=$1
JAR=$2

CAG=/export/common/data/processed/concrete/concretely-annotated/gigaword/stanford

echo "extracting raw data... `date`"
mkdir -p "$OUTPUT_DIR/raw"
java -ea -cp $JAR \
  edu.jhu.hlt.ikbp.tac.IndexCommunications \
    command extractBasicData \
    communicationArchives "$CAG/**/nyt_eng_2007*.tar.gz" \
    outputDirectory "$OUTPUT_DIR/raw"

echo "building document vectors... `date`"
mkdir -p "$OUTPUT_DIR/doc"
java -ea -cp $JAR \
  edu.jhu.hlt.ikbp.tac.IndexCommunications \
    command buildDocVecs \
    numTerms 128 \
    input "$OUTPUT_DIR/raw/termDoc.txt.gz" \
    outputDocVecs "$OUTPUT_DIR/doc/docVecs.txt.gz" \
    outputIdf "$OUTPUT_DIR/doc/idf.txt"

echo "done. `date`"

