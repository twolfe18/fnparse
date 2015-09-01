#!/bin/bash

set -e

if [[ $# != 6 ]]; then
  echo "please provide:"
  echo "1) how many templates to keep -- size of the feature set"
  echo "2) feature mode, i.e. \"LOCAL\", \"ARG-LOCATION\", \"NUM-ARGS\", \"ROLE-COOC\", \"FULL\""
  echo "3) a working directory for output"
  echo "4) a jar file with all dependencies"
  echo "5) how many workers to use"
  echo "6) port for the param server to use -- should be globally unique"
  exit -1
fi

NUM_TEMPLATES=$1
FEAT_MODE=$2
WORKING_DIR=$3
JAR=$4
NUM_WORKERS=$5
PARAM_SERVER_PORT=$6

mkdir -p $WORKING_DIR/sge-logs

echo "copying the jar to a safe place"
JAR_STABLE=$WORKING_DIR/fnparse.jar
echo "    $JAR"
echo "==> $JAR_STABLE"
cp $JAR $JAR_STABLE


# Make the feature set
FEATURES=experiments/feature-information-gain/feature-sets/fs-${NUM_TEMPLATES}.txt
#rm $FEATURES
#make $FEATURES
if [[ ! -f $FEATURES ]]; then
  echo "no feature set file: $FEATURES"
  exit -2
fi
FEATURES_STABLE=$WORKING_DIR/features.txt
cp $FEATURES $FEATURES_STABLE


# NOTE: This can fail because the port may already be in use.
# IF this happens for the parse server, this just means that things will be a little slower than they have to be.
# IF this happens for the param server, we're screwed!
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
PARSE_SERVER="nope"
while [[ `echo $PARSE_SERVER | grep -cP 'r\d+n\d+'` != 1 ]]; do
  sleep 5
  PARSE_SERVER=`qstat | grep 'parse-serv*' | tail -n 1 | cut -d'@' -f2 | cut -d'.' -f1`
  echo "tried again, server=$PARSE_SERVER"
done
echo "Found parse server: $PARSE_SERVER"


echo "Starting param server..."
mkdir -p $WORKING_DIR/server
qsub -N "param-server-$NUM_TEMPLATES" -q all.q -o $WORKING_DIR/sge-logs \
  ./scripts/propbank-train-server.sh \
    ${WORKING_DIR}/server \
    ${JAR_STABLE} \
    ${PARAM_SERVER_PORT}

PARAM_SERVER="nope"
while [[ `echo $PARAM_SERVER | grep -cP 'r\d+n\d+'` != 1 ]]; do
  sleep 5
  PARAM_SERVER=`qstat | grep 'param-serv*' | tail -n 1 | cut -d'@' -f2 | cut -d'.' -f1`
  echo "tried again, server=$PARAM_SERVER"
done
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
      ${PARAM_SERVER_PORT} \
      ${i} \
      ${NUM_WORKERS} \
      ${JAR_STABLE} \
      ${FEATURES_STABLE} \
      ${FEAT_MODE}
done


