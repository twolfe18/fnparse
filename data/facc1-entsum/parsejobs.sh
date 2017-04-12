#!/bin/bash

set -eu

# Lookes for files called 'raw.conll', creates 'parsed.conll' next to it.
# e.g. /export/projects/twolfe/entity-summarization/clueweb-linked/entsum-data/tokenized-sentences/dev
INPUT_DIR=$1

#for RAW in `find $INPUT_DIR -name 'raw.conll'`; do
#  DIR=`dirname $RAW`
#  PARSED="$DIR/parse.conll"
#  #echo "$RAW => $PARSED"
#  qsub -o $DIR /home/hltcoe/twolfe/syntaxnet/parsejob-conll.sh $RAW $PARSED
#done

### This code checks if the work is actually needed before qsubbing.
### I started some jobs on rare2 which take forever, bocking other progress, then killed the jobs.
### This checkpointing will let me kill/restart jobs and not loose too much work.
for RAW in `find $INPUT_DIR -name 'raw.conll'`; do
  DIR=`dirname $RAW`
  PARSED="$DIR/parse.conll"
  if [[ -f $PARSED ]]; then
    A=`cat $RAW | wc -l`
    B=`cat $PARSED | wc -l`
    if [[ $A == $B ]]; then
      echo "Skipping $PARSED since is already exists and matches $RAW"
      continue
    fi
  fi
  echo "$RAW => $PARSED"
  qsub -o $DIR /home/hltcoe/twolfe/syntaxnet/parsejob-conll.sh $RAW $PARSED
done


