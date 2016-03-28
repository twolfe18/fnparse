
# A script to compute IG/MI in multiple ways

set -eu

WORKING_DIR=$1        # root of data dir, doesn't include e.g. $ENTROPY_METHOD
ENTROPY_METHOD=$2     # MAP, MLE, BUB
LABEL_TYPE=$3         # frames or roles
IS_PROPBANK=$4
JAR=$5
SUF=$6

TEST_SET_SENT_IDS=$WORKING_DIR/test-set-sentence-ids.txt
if [[ ! -f $TEST_SET_SENT_IDS ]]; then
  echo "couldn't find test sent ids: $TEST_SET_SENT_IDS"
  exit 1
fi

# ig/
#   bub/
#     fnparse.jar
#     products/
#       ig-files/
#       sge-logs/
#     templates/
#       full
#       split10
#       split10-filter10
#       sge-logs/
#     feature-sets/
#       16-1280.fs

# This for the output for a specific run of this script
WD=$WORKING_DIR/ig/$ENTROPY_METHOD
echo "putting output in $WD"
#sleep 10  # a chance to kill/abort
mkdir -p $WD


JAR=target/fnparse-1.0.6-SNAPSHOT-jar-with-dependencies.jar
JAR_STABLE=$WD/fnparse.jar
echo "copying the jar to a safe place..."
echo "    $JAR"
echo "==> $JAR_STABLE"
cp $JAR $JAR_STABLE

# Used for capturing SGE output, for example
TEMP=$WD/tempfile.txt


# Figure out how many roles there are.
# This is needed for indexing (y/role, x/feature) pairs.
# NOTE: index+1 goes from 0-index to count, and index+2 also accounts for
# InformationGain.ADD_ONE=true which uses -1 as "noRole" => 0 as "noRole".
ROLE_DICT="$WORKING_DIR/raw-shards/job-0-of-*/role-names.txt*"
if [[ $LABEL_TYPE == "roles" ]]; then
  NUM_ROLES=`compress-cat $ROLE_DICT | grep -oP '\d+(?=	r=.+)' | tail -n 1 | awk '{print $1+2}'`
elif [[ $LABEL_TYPE == "frames" ]]; then
  NUM_ROLES=`compress-cat $ROLE_DICT | grep -oP '\d+(?=	f=.+)' | tail -n 1 | awk '{print $1+2}'`
else
  echo "illegal LABEL_TYPE: $LABEL_TYPE"
  exit 2
fi
echo "computed there are $NUM_ROLES from looking at $ROLE_DICT"


### Step 1 ####################################################################
echo "Computing IG for single templates @frame@role..."
FEATS=$WORKING_DIR/coherent-shards/features
BIALPH=$WORKING_DIR/coherent-shards/alphabet.txt$SUF
mkdir -p $WD/templates/ig-files
mkdir -p $WD/templates/sge-logs
MEM=6
NUM_SHARDS=500
for I in `seq $NUM_SHARDS | awk '{print $i-1}'`; do
  OUTPUT=$WD/templates/ig-files/shard-${I}-of-${NUM_SHARDS}.txt.gz
  SHARD="$I/$NUM_SHARDS"
  qsub -o $WD/templates/sge-logs \
    ./scripts/precompute-features/compute-ig.sh \
      $FEATS \
      "glob:**/*" \
      $BIALPH \
      $OUTPUT \
      $ENTROPY_METHOD \
      $LABEL_TYPE \
      $IS_PROPBANK \
      $ROLE_DICT \
      $TEST_SET_SENT_IDS \
      $NUM_ROLES \
      $SHARD \
      $JAR_STABLE \
      $MEM
done


### Step 2 ####################################################################
echo "Reducing template@frame@role PMI scores to single template scores..."
# TODO call combine-mutual-information.py
# Crucial questions that I'd like answered:
# - exp vs max
# - score vs rank
# => this gives me 3 at a minimum, but I can just do the fourth side of the square for completeness
# exp+score (baseline)    1 0 0 1 0
# exp+rank                1 0 0 0 1
# max+score               0 1 0 1 0
# max+rank                1 0 0 0 1


### Step 3 ####################################################################
echo "Computing IG for product features heuristically chosen from template IG scores..."
# NOTE: The more shards the more products we compute on

# TODO This is probably broken!
DEPS=`grep -oP '(?<=Your job )(\d+)' $TEMP | tr '\n' ',' | perl -pe 's/(.*),/\1\n/'`

OUTPUT=$WD/templates/ig-files/shard-${I}-of-${NUM_SHARDS}.txt.gz

# TODO Get this from output of step 2...
TEMPLATE_IG_FILE=$WORKING_DIR/ig/templates/full-ig.txt

PROD_IG_WD=$WORKING_DIR/ig/products
mkdir -p $PROD_IG_WD/ig-files
mkdir -p $PROD_IG_WD/sge-logs
NUM_SHARDS=500
FEATS_PER_SHARD=500
for i in `seq $NUM_SHARDS | awk '{print $1 - 1}'`; do
  qsub -hold_jid $DEPS -N prod-ig-$i -o $PROD_IG_WD/sge-logs \
    ./scripts/precompute-features/compute-ig-products.sh \
      $i \
      $NUM_SHARDS \
      $FEATS_PER_SHARD \
      $TEMPLATE_IG_FILE \
      $WORKING_DIR/coherent-shards-filtered/features \
      "glob:**/*" \
      $WORKING_DIR/coherent-shards-filtered/alphabet.txt$SUF \
      $TEST_SET_SENT_IDS \
      $PROD_IG_WD/ig-files/shard-${i}-of-${NUM_SHARDS}.txt \
      $JAR_STABLE \
      $NUM_ROLES
done


### Step 4 ####################################################################
echo "Reducing template@frame@role PMI scores to single feature scores..."
# TODO


### Step 5 ####################################################################
echo "Computing feature sets based on IG scores and apparent redundancy (by feature name)..."
# TODO see scripts/having-a-laugh






