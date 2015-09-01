#!/bin/bash

set -e

if [[ $# != 5 ]]; then
  echo "please provide:"
  echo "1) how many templates to keep -- size of the feature set"
  echo "2) feature mode, i.e. \"LOCAL\", \"ARG-LOCATION\", \"NUM-ARGS\", \"ROLE-COOC\", \"FULL\""
  echo "3) a working directory for output"
  echo "4) a jar file with all dependencies"
  echo "5) how many workers to use"
  exit -1
fi

NUM_TEMPLATES=$1
FEAT_MODE=$2
WORKING_DIR=$3
JAR=$4
NUM_WORKERS=$5

mkdir -p $WORKING_DIR/sge-logs

# Make the feature set
FEATURES=experiments/feature-information-gain/feature-sets/fs-${NUM_TEMPLATES}.txt
rm $FEATURES
make $FEATURES


echo "Starting redis server for parses..."
qsub -N "parse-server-$NUM_TEMPLATES" -q all.q -o $WORKING_DIR/sge-logs \
  ./scripts/propbank-train-redis-parse-server.sh

# NOTE: SGE seems to be seriously retarded. There doesn't appear to be a way to
# get the machine that a specific job is running on. It is listed as a column
# in the vanilla `qstat` table, but I cannot get it any other way (including
# appearling to the -xml output!)

# Check where the server is running.
# Approximation: Take the last job that matches "param-server*"
#sleep `seq 3 10 | shuf -n 1`
sleep 5
PARSE_SERVER=`qstat | grep 'parse-serv*' | tail -n 1 | cut -d'@' -f2 | cut -d'.' -f1`
echo "Found parse server: $PARSE_SERVER"


echo "Starting param server..."
mkdir -p $WORKING_DIR/server
qsub -N "param-server-$NUM_TEMPLATES" -q all.q -o $WORKING_DIR/sge-logs \
  ./scripts/propbank-train-server.sh $WORKING_DIR/server $JAR

sleep 5
PARAM_SERVER=`qstat | grep 'param-serv*' | tail -n 1 | cut -d'@' -f2 | cut -d'.' -f1`
echo "Found param server: $PARAM_SERVER"


echo "Starting the clients..."
for i in `seq $NUM_WORKERS | awk '{print $1-1}'`; do
  WD=${WORKING_DIR}/client-${i}
  mkdir -p $WD
  qsub -N "client-$i-of-$NUM_WORKERS-nTmpl_$NUM_TEMPLATES" -q all.q -o $WORKING_DIR/sge-logs \
    ./scripts/propbank-train-client.sh \
      ${WD} \
      ${PARSE_SERVER} \
      ${PARAM_SERVER} \
      ${i} \
      ${NUM_WORKERS} \
      ${JAR} \
      ${FEATURES}
done


