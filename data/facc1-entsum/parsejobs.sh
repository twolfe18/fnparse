#!/bin/bash

set -eu

# Lookes for files called 'raw.conll', creates 'parsed.conll' next to it.
# e.g. /export/projects/twolfe/entity-summarization/clueweb-linked/entsum-data/tokenized-sentences/dev
INPUT_DIR=$1

for RAW in `find $INPUT_DIR -name 'raw.conll'`; do
  DIR=`dirname $RAW`
  PARSED="$DIR/parse.conll"
  #echo "$RAW => $PARSED"
  qsub -o $DIR /home/hltcoe/twolfe/syntaxnet/parsejob-conll.sh $RAW $PARSED
done

