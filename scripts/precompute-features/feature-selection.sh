
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
  SHARD="$I/$NUM_SHARDS"
  qsub -o $WD/templates/sge-logs \
    ./scripts/precompute-features/compute-ig.sh \
      $FEATS \
      "glob:**/*" \
      $BIALPH \
      $WD/templates/ig-files/pos-shard-${I}-of-${NUM_SHARDS}.txt.gz \
      $ENTROPY_METHOD \
      $LABEL_TYPE \
      "FRAME" \
      $IS_PROPBANK \
      $ROLE_DICT \
      $TEST_SET_SENT_IDS \
      $NUM_ROLES \
      $SHARD \
      $JAR_STABLE \
      $MEM
  qsub -o $WD/templates/sge-logs \
    ./scripts/precompute-features/compute-ig.sh \
      $FEATS \
      "glob:**/*" \
      $BIALPH \
      $WD/templates/ig-files/neg-shard-${I}-of-${NUM_SHARDS}.txt.gz \
      $ENTROPY_METHOD \
      $LABEL_TYPE \
      "NULL_LABEL" \
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
# TODO Need to capture the dependencies of the previous set of jobs.
# Call combine-mutual-information.py
# Crucial questions that I'd like answered:
# - exp vs max
# - score vs rank
# => this gives me 3 at a minimum, but I can just do the fourth side of the square for completeness
# exp+score (baseline)    1 0 0 1 0
# exp+rank                1 0 0 0 1
# max+score               0 1 0 1 0
# max+rank                1 0 0 0 1
for C_EXP in 0 1; do
for C_MAX in 0 1; do
for C_BOTH in 0 1; do
if [[ `echo "$C_EXP + $C_MAX + $C_BOTH" | bc` == 0 ]]; then
  continue
fi
for C_ABS in 0 1; do
for C_RBC in 0 1; do
if [[ `echo "$C_ABS + $C_RBC" | bc` == 0 ]]; then
  continue
fi

# TODO Now that I have positive and negative features,
# am I just going to keep these as separate pipelines the
# whole way through?

qsub -l 'h_rt=72:00:00,num_proc=2,mem_free=18G' \
  compress-cat $WD/templates/ig-files/shard-*-of-${NUM_SHARDS}.txt.gz \
  | PYTHONPATH=scripts/having-a-laugh python \
    scripts/precompute-features/combine-mutual-information.py $C_EXP $C_MAX $C_BOTH $C_ABS $C_RBC  \
  | PYTHONPATH=scripts/having-a-laugh python \
    scripts/precompute-features/combine-mutual-information.py $C_EXP $C_MAX $C_BOTH $C_ABS $C_RBC  \
  | sort -rg -k2 \
  >$WD/templates/best-features-${C_EXP}${C_MAX}${C_BOTH}${C_ABS}${C_RBC}.txt
done
done
done
done
done



### Step 3 ####################################################################
echo "Computing IG for product features heuristically chosen from template IG scores..."
# NOTE: The more shards the more products we compute on

# TODO This is probably broken!
DEPS=`grep -oP '(?<=Your job )(\d+)' $TEMP | tr '\n' ',' | perl -pe 's/(.*),/\1\n/'`

# TODO Get this from output of step 2...
C_EXP=1
C_MAX=0
C_BOTH=0
C_ABS=1
C_RBC=0
C_STRING="${C_EXP}${C_MAX}${C_BOTH}${C_ABS}${C_RBC}"
TEMPLATE_IG_FILE=$WD/templates/best-features-${C_STRING}.txt    # input
PROD_IG_WD=$WD/products/$C_STRING # output
mkdir -p $PROD_IG_WD/ig-files
mkdir -p $PROD_IG_WD/sge-logs
NUM_SHARDS=500
FEATS_PER_SHARD=500
for I in `seq $NUM_SHARDS | awk '{print $1 - 1}'`; do
  SHARD="$I/$NUM_SHARDS"
  qsub -o $PROD_IG_WD/sge-logs \
    ./scripts/precompute-features/compute-ig-products.sh \
      $FEATS \
      "glob:**/*" \
      $BIALPH \
      $PROD_IG_WD/ig-files/pos-shard-${I}-of-${NUM_SHARDS}.txt.gz \
      $ENTROPY_METHOD \
      $LABEL_TYPE \
      "FRAME" \
      $IS_PROPBANK \
      $ROLE_DICT \
      $TEST_SET_SENT_IDS \
      $NUM_ROLES \
      $SHARD \
      $JAR_STABLE \
      $MEM \
      $FEATS_PER_SHARD \
      $TEMPLATE_IG_FILE
  qsub -o $PROD_IG_WD/sge-logs \
    ./scripts/precompute-features/compute-ig-products.sh \
      $FEATS \
      "glob:**/*" \
      $BIALPH \
      $PROD_IG_WD/ig-files/neg-shard-${I}-of-${NUM_SHARDS}.txt.gz \
      $ENTROPY_METHOD \
      $LABEL_TYPE \
      "NULL_LABEL" \
      $IS_PROPBANK \
      $ROLE_DICT \
      $TEST_SET_SENT_IDS \
      $NUM_ROLES \
      $SHARD \
      $JAR_STABLE \
      $MEM \
      $FEATS_PER_SHARD \
      $TEMPLATE_IG_FILE
done


### Step 4 ####################################################################
echo "Reducing template@frame@role PMI scores to single feature scores..."
# TODO
OUTPUT=$PROD_IG_WD/ig-files/shard-${I}-of-${NUM_SHARDS}.txt.gz


### Step 5 ####################################################################
echo "Computing feature sets based on IG scores and apparent redundancy (by feature name)..."
# TODO see scripts/having-a-laugh






