#!/bin/bash

# Given some Concrete with targets/predicates/frames labeled, run a serialized
# SRL model (created by ShimModel) to produce Concrete output with SituationMentions.
# Handles feature extraction and indexing.

# NOTE: I think I'm going to pause on working on this due to the fact that it doesn't
# handle frame id (or target id for that matter). I'll keep it in case I change my mind,
# and as a good example of how to do feature-reindexing, but for the ability to do real
# predictions, I'm going to use uberts.


set -eu

MODEL=$1            # serialized FModel built by ShimModel
BIALPH=$2           # alphabet used to index the feature files the MODEL was trained with, e.g. coherent-shards-filtered-small/alphabet.txt
FEATURE_SET=$3      # feature set the MODEL was trained with, e.g. ~twolfe/fnparse/scripts/having-a-laugh/propbank-16-1280.fs
INPUT_CONCRETE=$4   # tar.gz file of concrete.Communications which have been put through concrete-stanford
OUTPUT_DIR=$5       # a directory which this script can put output into
JAR=$6              # fnparse.jar, see `make jar` in fnparse/

### Output
# newInt* are indices model knows about, oldInt* are indices in FEATURE_SET
OUTPUT_BIALPH_SMALL=$OUTPUT_DIR/bialph-minimal.txt
# like INPUT_CONCRETE but with annotation added
OUTPUT_CONCRETE=$OUTPUT_DIR/output-communications.tgz
# directory containing the features extracted from INPUT_CONCRETE
OUTPUT_FEATS_STR=$OUTPUT_DIR/string-features
# feature file that has same indices as BIALPH
OUTPUT_FEATS_INDEXED=$OUTPUT_DIR/output-features-indexed.txt.gz

echo "starting at `date`"

echo "copying jar into safe place..."
JAR_STABLE=$OUTPUT_DIR/fnparse.jar
echo "copying the jar to a safe place..."
echo "    $JAR"
echo "==> $JAR_STABLE"
cp $JAR $JAR_STABLE

echo "Extracting raw (mis-indexed) features..."
mkdir -p $OUTPUT_FEATS_STR
java -ea -cp $JAR_STABLE edu.jhu.hlt.fnparse.features.precompute.FeaturePrecomputation \
  workingDir $OUTPUT_FEATS_STR \
  dataset "concrete:$INPUT_CONCRETE"
TEMP_ALPH=$OUTPUT_FEATS_STR/template-feat-indices.txt
if [[ ! -f $TEMP_ALPH ]]; then
  echo "failure: didn't find output alphabet: $TEMP_ALPH"
  exit 1
fi
TEMP_FEATS=$OUTPUT_FEATS_STR/features.txt
if [[ ! -f $TEMP_FEATS ]]; then
  echo "failure: didn't find output features: $TEMP_FEATS"
  exit 1
fi

echo "Creating a BiAlph representing the intersection of the new and old templates/features..."
java -ea -cp $JAR_STABLE edu.jhu.hlt.fnparse.features.precompute.BiAlphIntersection \
  $BIALPH \
  $TEMP_ALPH \
  $OUTPUT_BIALPH_SMALL \

echo "Projecting the features with the intersection BiAlph to match up indices for the MODEL..."
java -ea -cp $JAR_STABLE edu.jhu.hlt.fnparse.features.precompute.BiAlphProjection \
  inputBialph $OUTPUT_BIALPH_SMALL \
  lineMode BIALPH \
  inputFeatures $TEMP_FEATS \
  outputFeatures $OUTPUT_FEATS_INDEXED

echo "Making predictions..."
java -ea -cp $JAR_STABLE edu.jhu.hlt.fnparse.rl.full.FModel \
  $MODEL \
  $INPUT_CONCRETE \
  $OUTPUT_FEATS_INDEXED \
  $OUTPUT_CONCRETE

echo "done at `date`"

