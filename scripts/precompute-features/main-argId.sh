
# How to run all the scripts in this directory by hand.
# By hand because it needs to be asyncronous (qsub takes a while and doesn't block)

#export FNPARSE_DATA=/export/projects/twolfe/fnparse-data
export FNPARSE_DATA=$HOME/scratch/fnparse-data

#SUF=".gz"
SUF=".bz2"

# TODO Put these in case statement
CAT="bzcat"
ZIP="bzip2 -c"

BACKEND=slurm
export CLUSTERLIB_BACKEND=$BACKEND

DATASET=framenet
#WORKING_DIR=experiments/precompute-features/propbank/sep14a/raw-shards
#WORKING_DIR=/export/projects/twolfe/fnparse-output/experiments/precompute-features/propbank/sep14b/raw-shards
#WORKING_DIR=/export/projects/twolfe/fnparse-output/experiments/precompute-features/framenet/sep29a/raw-shards
#WORKING_DIR=/export/projects/twolfe/fnparse-output/experiments/for-oct-tacl/framenet/oct21a/
#WORKING_DIR=/export/projects/twolfe/fnparse-output/experiments/for-oct-tacl/$DATASET/oct21a/
WORKING_DIR=/export/projects/twolfe/fnparse-output/experiments/dec-experiments/$DATASET
JAR=target/fnparse-1.0.6-SNAPSHOT-jar-with-dependencies.jar

############################################################################################
### CREATE MANY SHARDS OF FEATURES

# Start redis parse servers.
# These slightly an accident of history with some meh reasons:
# 1) I didn't originally serialize the parses with the Propbank data
# 2) I wanted a way to cache parses across machines
# 3) I can fit all of the FNParses in memory, but not with the parses as well
#REDIS_CONF=scripts/propbank-train-redis-parse-server.redisconf
REDIS_CONF=/export/projects/twolfe/fnparse-data/cache/parses/propbank/redis/propbank-train-redis-parse-server.redisconf
PORT=6379   # default
qsub -N parse-fPreComp ./scripts/propbank-train-redis-parse-server.sh $REDIS_CONF
qsub -N parse-fPreComp ./scripts/propbank-train-redis-parse-server.sh $REDIS_CONF
qsub -N parse-fPreComp ./scripts/propbank-train-redis-parse-server.sh $REDIS_CONF
qsub -N parse-fPreComp ./scripts/propbank-train-redis-parse-server.sh $REDIS_CONF
# Now look up the machines these were dispatched to and copy those namaes into the array below.

#NUM_SHARDS=256   # propbank
NUM_SHARDS=32    # framenet
#PARSE_REDIS_SERVERS=(r5n07 r5n20 r5n34 r6n28)
PARSE_REDIS_SERVERS=(r6n27 r6n35 r6n24 r6n03)

mkdir -p $WORKING_DIR/raw-shards/sge-logs

echo "copyinig jar to a safe place..."
JAR_STABLE=$WORKING_DIR/raw-shards/fnparse.jar
echo "    $JAR"
echo "==> $JAR_STABLE"
cp $JAR $JAR_STABLE

echo "starting at `date`" >>$WORKING_DIR/raw-shards/commands.txt
ROLE_ID=true
for i in `seq $NUM_SHARDS | awk '{print $1 - 1}'`; do
  WD=$WORKING_DIR/raw-shards/job-$i-of-$NUM_SHARDS
  #J=`echo $i | awk '{print $1 % 4}'`
  J=0
  PS=${PARSE_REDIS_SERVERS[$J]}
  echo "dispatching to $PS"
  mkdir -p $WD
  COMMAND="qsub -N fPreComp-$i-$NUM_SHARDS \
    -o $WORKING_DIR/raw-shards/sge-logs \
    scripts/precompute-features/extract-one-shard.sh \
      $WD \
      $JAR_STABLE \
      $i \
      $NUM_SHARDS \
      $PS \
      $PORT \
      $DATASET \
      $ROLE_ID \
      $SUF"
   echo $COMMAND >>$WORKING_DIR/raw-shards/commands.txt
   eval $COMMAND
done


############################################################################################
### MERGE MANY SHARDS INTO A COHERENT ALPHABET
#WORKING_DIR=/export/projects/twolfe/fnparse-output/experiments/precompute-features/framenet/sep29a
python -u scripts/precompute-features/bialph-merge-pipeline.py \
  $WORKING_DIR \
  $NUM_SHARDS \
  $SUF \
  $JAR \
  | tee /tmp/merge-job-launch-log.txt


############################################################################################
### CREATE TEMPLATE FILTERS
# e.g. "only have a feature fire if it is in the top K most frequent by this template"
# or "only have this feature fire if is has fired at least K other times in the corpus"
# These features will be put at the end of every line in the feature files and a new
# alphabet will be writte out including them.

# A) Compute the feature frequencies
BIALPH_BEFORE_FILTERS=$WORKING_DIR/coherent-shards/alphabet.txt$SUF
FEATS_BEFORE_FILTERS=$WORKING_DIR/coherent-shards/features
mkdir -p $WORKING_DIR/feature-counts/sge-logs

echo "copyinig jar to a safe place..."
JAR_STABLE=$WORKING_DIR/feature-counts/fnparse.jar
echo "    $JAR"
echo "==> $JAR_STABLE"
cp $JAR $JAR_STABLE

qsub -N featCounts-full \
  -b y -j y -V -o $WORKING_DIR/feature-counts/sge-logs \
  -l 'mem_free=25G,num_proc=1,h_rt=12:00:00' \
  java -cp $JAR_STABLE -Xmx24G -ea -server \
    -Dbialph=$BIALPH_BEFORE_FILTERS \
    -Doutput=$WORKING_DIR/feature-counts/all.txt$SUF \
    -DfeaturesParent=$FEATS_BEFORE_FILTERS \
    edu.jhu.hlt.fnparse.features.precompute.FeatureCounts
qsub -N featCounts-tenth \
  -b y -j y -V -o $WORKING_DIR/feature-counts/sge-logs \
  -l 'mem_free=25G,num_proc=1,h_rt=12:00:00' \
  java -cp $JAR_STABLE -Xmx15G -ea -server \
    -Dbialph=$BIALPH_BEFORE_FILTERS \
    -Doutput=$WORKING_DIR/feature-counts/one-tenth.txt$SUF \
    -DfeaturesGlob="glob:**/shard*9.txt$SUF" \
    -DfeaturesParent=$FEATS_BEFORE_FILTERS \
    edu.jhu.hlt.fnparse.features.precompute.FeatureCounts
qsub -N featCounts-hundreth \
  -b y -j y -V -o $WORKING_DIR/feature-counts/sge-logs \
  -l 'mem_free=25G,num_proc=1,h_rt=12:00:00' \
  java -cp $JAR_STABLE -Xmx15G -ea -server \
    -Dbialph=$BIALPH_BEFORE_FILTERS \
    -Doutput=$WORKING_DIR/feature-counts/one-hundredth.txt$SUF \
    -DfeaturesGlob="glob:**/shard*99.txt$SUF" \
    -DfeaturesParent=$FEATS_BEFORE_FILTERS \
    edu.jhu.hlt.fnparse.features.precompute.FeatureCounts


# B) Generate the filtered features based on the frequencies
#   (if i try to do ~60G => +filters:~8x => 250G with one process... it is going to take a while...)
mkdir -p $WORKING_DIR/coherent-shards-filtered/sge-logs
mkdir -p $WORKING_DIR/coherent-shards-filtered/features
BIALPH_AFTER_FILTERS=$WORKING_DIR/coherent-shards-filtered/alphabet.txt$SUF

# Make sure this is done!
#COUNT_FILE=$WORKING_DIR/feature-counts/one-tenth.txt$SUF
COUNT_FILE=$WORKING_DIR/feature-counts/all.txt$SUF

echo "copyinig jar to a safe place..."
JAR_STABLE=$WORKING_DIR/coherent-shards-filtered/fnparse.jar
echo "    $JAR"
echo "==> $JAR_STABLE"
cp $JAR $JAR_STABLE

# Takes shard 0 and builds output bialph
qsub -N genFiltered-alph \
  -b y -j y -V -o $WORKING_DIR/coherent-shards-filtered/sge-logs \
  -l 'mem_free=20G,num_proc=1,h_rt=12:00:00' \
  java -cp $JAR_STABLE -Xmx19G -ea -server \
    -Dshard=0 \
    -DnumShards=$NUM_SHARDS \
    -DcountFile=$COUNT_FILE \
    -DoutputBialph=$BIALPH_AFTER_FILTERS \
    -Dbialph=$BIALPH_BEFORE_FILTERS \
    -DoutputFeatureFileDir=$WORKING_DIR/coherent-shards-filtered/features \
    -DfeaturesGlob="glob:**/*" \
    -DfeaturesParent=$WORKING_DIR/coherent-shards/features \
    edu.jhu.hlt.fnparse.features.precompute.TemplateTransformer
for SHARD in `echo "$NUM_SHARDS-1" | bc | xargs seq`; do    # takes shards 1..
  qsub -N genFiltered \
    -b y -j y -V -o $WORKING_DIR/coherent-shards-filtered/sge-logs \
    -l 'mem_free=20G,num_proc=1,h_rt=12:00:00' \
    java -cp $JAR_STABLE -Xmx19G -ea -server \
      -Dshard=$SHARD \
      -DnumShards=$NUM_SHARDS \
      -DcountFile=$COUNT_FILE \
      -Dbialph=$BIALPH_BEFORE_FILTERS \
      -DoutputFeatureFileDir=$WORKING_DIR/coherent-shards-filtered/features \
      -DfeaturesGlob="glob:**/*" \
      -DfeaturesParent=$WORKING_DIR/coherent-shards/features \
      edu.jhu.hlt.fnparse.features.precompute.TemplateTransformer
done


# Useful for debugging: generate a map between template <-> int
# This comes from the bialph, but it is generally too large to be handled conveniently
$CAT $WORKING_DIR/coherent-shards-filtered/alphabet.txt$SUF | awk -F"\t" 'BEGIN{OFS="\t"} {print $1, $3}' | uniq >$WORKING_DIR/template-names.txt




############################################################################################
### COMPUTE INFORMATION GAIN
#WD=/export/projects/twolfe/fnparse-output/experiments/precompute-features/propbank/sep14b
#WD=/export/projects/twolfe/fnparse-output/experiments/precompute-features/framenet/sep29a
FNPARSE_DATA=/export/projects/twolfe/fnparse-data/

# 0) Find the sentence ids of the test set and don't use these for computing information gain
TEST_SET_SENT_IDS="$WORKING_DIR/test-set-sentence-ids.txt"
DP=/export/projects/twolfe/fnparse-data/
mvn exec:java \
  -Dexec.mainClass=edu.jhu.hlt.fnparse.features.precompute.DumpSentenceIds \
  -Ddata.framenet.root=$FNPARSE_DATA \
  -Ddata.wordnet=$FNPARSE_DATA/wordnet/dict \
  -Ddata.ontonotes5=/export/common/data/corpora/LDC/LDC2013T19/data/files/data/english/annotations \
  -Ddata.propbank.conll=$DP/conll-formatted-ontonotes-5.0/conll-formatted-ontonotes-5.0/data \
  -Ddata.propbank.frames=$DP/ontonotes-release-5.0-fixed-frames/frames \
  -Ddata=$DATASET \
  -Dpart=test \
  -Doutput=$TEST_SET_SENT_IDS

# NUM_ROLES can be determined by looking at:
ROLE_DICT="$WORKING_DIR/raw-shards/job-0-of-$NUM_SHARDS/role-names.txt$SUF"
NUM_ROLES=`$CAT $ROLE_DICT | wc -l`
echo "NUM_ROLES=$NUM_ROLES from $ROLE_DICT"

# Santity check: all of these alphabets should be the same:
diff <($CAT $ROLE_DICT) <($CAT $WORKING_DIR/raw-shards/job-1-of-$NUM_SHARDS/role-names.txt$SUF)


# 1) For each template
mkdir -p $WORKING_DIR/ig/templates/sge-logs

JAR=target/fnparse-1.0.6-SNAPSHOT-jar-with-dependencies.jar
JAR_STABLE=$WORKING_DIR/ig/templates/fnparse.jar
echo "copying the jar to a safe place..."
echo "    $JAR"
echo "==> $JAR_STABLE"
cp $JAR $JAR_STABLE

# Do a 1/10 estimate of 1/10 of the data and average the answers (should take 1/10th the time but be approximate)
N=10
XMX=32
XMX_SGE=36
mkdir -p $WORKING_DIR/ig/templates/split-$N-filter10
for i in `seq $N | awk '{print $1-1}'`; do
  qsub -N split$N-filter10-$i-ig-template \
    -l "mem_free=${XMX_SGE}G" \
    -o $WORKING_DIR/ig/templates/sge-logs \
    scripts/precompute-features/compute-ig.sh \
      $WORKING_DIR/coherent-shards-filtered/features \
      "glob:**/*1${i}.txt$SUF" \
      $WORKING_DIR/ig/templates/split-$N-filter10/shard-${i}.txt \
      $JAR_STABLE \
      $TEST_SET_SENT_IDS \
      $NUM_ROLES \
      $XMX
done

# Do a 1/10 estimate and average the answers (should take 1/10th the time but be approximate)
N=10
XMX=60
XMX_SGE=65
mkdir -p $WORKING_DIR/ig/templates/split-$N
for i in `seq $N | awk '{print $1-1}'`; do
  qsub -N split$N-$i-ig-template \
    -l "mem_free=${XMX_SGE}G" \
    -o $WORKING_DIR/ig/templates/sge-logs \
    scripts/precompute-features/compute-ig.sh \
      $WORKING_DIR/coherent-shards-filtered/features \
      "glob:**/*${i}.txt$SUF" \
      $WORKING_DIR/ig/templates/split-$N/shard-${i}.txt \
      $JAR_STABLE \
      $TEST_SET_SENT_IDS \
      $NUM_ROLES \
      $XMX
done

# Do a full/proper estimate
XMX=80
XMX_SGE=85
qsub -N full-ig-template \
  -l "mem_free=${XMX_SGE}G" \
  -o $WORKING_DIR/ig/templates/sge-logs \
  scripts/precompute-features/compute-ig.sh \
    $WORKING_DIR/coherent-shards-filtered/features \
    "glob:**/*" \
    $WORKING_DIR/ig/templates/full-ig.txt \
    $JAR_STABLE \
    $TEST_SET_SENT_IDS \
    $NUM_ROLES \
    $XMX


### After those jobs finish, average things
python scripts/precompute-features/average-ig.py \
  $WORKING_DIR/ig/templates/split-$N/shard-* \
  >$WORKING_DIR/ig/templates/split-$N/average.txt


# 2) For prodcuts of templates, filtered by top K products ranked by product of template IG
# NOTE: The more shards the more products we compute on
#TEMPLATE_IG_FILE=$WORKING_DIR/ig/templates/split-$N/average.txt
TEMPLATE_IG_FILE=$WORKING_DIR/ig/templates/full-ig.txt
PROD_IG_WD=$WORKING_DIR/ig/products
mkdir -p $PROD_IG_WD/ig-files
mkdir -p $PROD_IG_WD/sge-logs
cp target/fnparse-1.0.6-SNAPSHOT-jar-with-dependencies.jar $PROD_IG_WD/fnparse.jar
NUM_SHARDS=500
FEATS_PER_SHARD=500
for i in `seq $NUM_SHARDS | awk '{print $1 - 1}'`; do
  qsub -N prod-ig-$i -o $PROD_IG_WD/sge-logs \
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
      $PROD_IG_WD/fnparse.jar \
      $NUM_ROLES
done





############################################################################################
### Build feature sets
# see Makefile in scripts/having-a-laugh

#DATASET=propbank
#NUM_SHARDS=256
DATASET=framenet
NUM_SHARDS=32
#WORKING_DIR=/export/projects/twolfe/fnparse-output/experiments/for-oct-tacl/$DATASET/oct21a/
WORKING_DIR=/export/projects/twolfe/fnparse-output/experiments/dec-experiments/$DATASET
WD=$WORKING_DIR/coherent-shards-filtered-small

## Build a filtered bilaph
# After all the filters, these alphabets will have hundreds of millions of
# features and will be hundreds of MB after gzipping. The bialphs which contain
# all the templates selected by feature selection can be less than a million
# feature (order of hundreds of templates).
# This step just removes unused templates from the full bialph.
BIALPH_FULL=$WORKING_DIR/coherent-shards-filtered/alphabet.txt$SUF
#BIALPH_SMALL=$WORKING_DIR/coherent-shards-filtered/alphabet.onlyTemplatesInFs.txt
BIALPH_SMALL=$WD/alphabet.txt

JAR=target/fnparse-1.0.6-SNAPSHOT-jar-with-dependencies.jar
JAR_STABLE=$WD/fnparse.jar
echo "copying the jar to a safe place..."
echo "    $JAR"
echo "==> $JAR_STABLE"
cp $JAR $JAR_STABLE

# I don't think I can afford 2560, 640 is already really slow
# cat framenet-*-640.fs | awk -F"\t" '{print $6}' | tr '*' '\n' | sort -u | wc -l
# 671
# cat framenet-*-1280.fs | awk -F"\t" '{print $6}' | tr '*' '\n' | sort -u | wc -l
# 1555
# cat framenet-*-2560.fs | awk -F"\t" '{print $6}' | tr '*' '\n' | sort -u | wc -l
# 3320
D=1280
java -cp target/fnparse-1.0.6-SNAPSHOT-jar-with-dependencies.jar -ea -server \
  edu.jhu.hlt.fnparse.features.precompute.BiAlphFilter \
    $BIALPH_FULL \
    $BIALPH_SMALL \
    scripts/having-a-laugh/${DATASET}-4-${D}.fs \
    scripts/having-a-laugh/${DATASET}-8-${D}.fs \
    scripts/having-a-laugh/${DATASET}-16-${D}.fs \
    scripts/having-a-laugh/${DATASET}-32-${D}.fs


# Filter the feature files to only includ ethe features in the alphabet
mkdir -p $WD/sge-logs
for i in `seq $NUM_SHARDS | awk '{print $1-1}'`; do
  qsub -N "featFilt-$i" -b y -j y -V -o $WD/sge-logs \
    -l 'num_proc=1,mem_free=5G,h_rt=48:00:00' \
    java -ea -server -Xmx4G -cp $JAR_STABLE \
      -DinputBialph=$BIALPH_SMALL \
      -DlineMode="ALPH_AS_TRIVIAL_BIALPH" \
      -DfeaturesParent=$WORKING_DIR/coherent-shards-filtered/features \
      -DoutputFeatures=$WD/features \
      -DstripOutputSuf=true \
      -Dshard=$i \
      -DnumShards=$NUM_SHARDS \
      edu.jhu.hlt.fnparse.features.precompute.BiAlphProjection
done


############################################################################################
# TODO Embed features?
# Just run word2vec on my feature files (after filtering by top K templates by MI maybe)?
# Pretty sure the embeddings will not be linear with the weights,
#   will want some type of RBF kernel perceptron (average alpha*K(x,x') over all features for a given instance as decision rule).




############################################################################################
### RUN THE ACTUAL EXPERIMENTS

# 1) see Makefile in scripts/having-a-laugh (build feature sets based on product IG + dedup)
# 2) launch experiments with scripts/precompute-features/train-all.sh


DATASET="framenet"

# Generate java serialized data for the final step
./scripts/precompute-features/train3-setup.sh $DATASET

WD=/export/projects/twolfe/fnparse-output/experiments/dec-experiments/$DATASET/experiment/jan26a
if [[ -d $WD ]]; then
  echo "already exists: $WD"
  echo "ARE YOUR SURE YOU WANT TO POSSIBLY OVERWRITE???"
  sleep 15
fi
FEAT_PARENT=/export/projects/twolfe/fnparse-output/experiments/dec-experiments/$DATASET/coherent-shards-filtered-small-jser
if [[ $DATASET == "framenet" ]]; then
  FEAT_DIR=$FEAT_PARENT/C16-D1280
else
  FEAT_DIR=$FEAT_PARENT/C8-D1280
fi

### Setup
mkdir -p $WD/sge-logs
JAR=target/fnparse-1.0.6-SNAPSHOT-jar-with-dependencies.jar
JAR_STABLE=$WD/fnparse.jar
echo "copying the jar to a safe place..."
echo "    $JAR"
echo "==> $JAR_STABLE"
cp $JAR $JAR_STABLE

if [[ $DATASET == "propbank" ]]; then
  MEM="46G"
  MEM_FREE="48G"
elif [[ $DATASET == "framenet" ]]; then
  MEM="10G"
  MEM_FREE="12G"
else
  echo "unknown dataset: $DATASET"
fi
echo "using MEM=$MEM MEM_FREE=$MEM_FREE"


# Train size (-DnTrain)
if [[ $DATASET == "propbank" ]]; then
  N=115812
elif [[ $DATASET == "framenet" ]]; then
  N=3500
else
  echo "unknown dataset: $DATASET"
fi
for D in 3 9 27 81 243; do
  C=`echo "$N / $D" | bc`
  if [[ $C -lt 100 ]]; then
    echo "data is too small (skipping): $C"
  else
    K="ntrain-$D"
    qsub -N $K -o $WD/sge-logs \
      -l "num_proc=1,mem_free=$MEM_FREE,h_rt=240:00:00" \
      ./scripts/precompute-features/train3.sh \
        $WD/$K $FEAT_DIR $DATASET $MEM $JAR_STABLE \
        nTrain $C
  fi
done


# Oracle (-DoracleMode)
for OM in RAND RAND_MIN RAND_MAX MIN MAX; do
  K="oracleMode-$OM"
  qsub -N $K -o $WD/sge-logs \
    -l "num_proc=1,mem_free=$MEM_FREE,h_rt=240:00:00" \
    ./scripts/precompute-features/train3.sh \
      $WD/$K $FEAT_DIR $DATASET $MEM $JAR_STABLE \
      oracleMode $OM
done


# Global features (see LLSSPatF)
qsub -N "global-NONE" -o $WD/sge-logs \
  -l "num_proc=1,mem_free=$MEM_FREE,h_rt=240:00:00" \
  ./scripts/precompute-features/train3.sh \
    $WD/global-NONE $FEAT_DIR $DATASET $MEM $JAR_STABLE \
    ARG_LOC false ROLE_COOC false NUM_ARGS false

qsub -N "global-ARG_LOC" -o $WD/sge-logs \
  -l "num_proc=1,mem_free=$MEM_FREE,h_rt=240:00:00" \
  ./scripts/precompute-features/train3.sh \
    $WD/global-ARG_LOC $FEAT_DIR $DATASET $MEM $JAR_STABLE \
    ARG_LOC true ROLE_COOC false NUM_ARGS false

qsub -N "global-ROLE_COOC" -o $WD/sge-logs \
  -l "num_proc=1,mem_free=$MEM_FREE,h_rt=240:00:00" \
  ./scripts/precompute-features/train3.sh \
    $WD/global-ROLE_COOC $FEAT_DIR $DATASET $MEM $JAR_STABLE \
    ARG_LOC false ROLE_COOC true NUM_ARGS false

qsub -N "global-NUM_ARGS" -o $WD/sge-logs \
  -l "num_proc=1,mem_free=$MEM_FREE,h_rt=240:00:00" \
  ./scripts/precompute-features/train3.sh \
    $WD/global-NUM_ARGS $FEAT_DIR $DATASET $MEM $JAR_STABLE \
    ARG_LOC false ROLE_COOC false NUM_ARGS true

qsub -N "global-FULL" -o $WD/sge-logs \
  -l "num_proc=1,mem_free=$MEM_FREE,h_rt=240:00:00" \
  ./scripts/precompute-features/train3.sh \
    $WD/global-FULL $FEAT_DIR $DATASET $MEM $JAR_STABLE \
    ARG_LOC true ROLE_COOC true NUM_ARGS true


# Egg sorting * Kmax
for SORT_MODE in NONE BY_MODEL_SCORE BY_EXPECTED_UTILITY; do
for KMAX in true false; do
  K="eggSort-${SORT_MODE}-kmax-${KMAX}"
  qsub -N $K -o $WD/sge-logs \
    -l "num_proc=1,mem_free=$MEM_FREE,h_rt=240:00:00" \
    ./scripts/precompute-features/train3.sh \
      $WD/$K $FEAT_DIR $DATASET $MEM $JAR_STABLE \
      sortEggsMode $SORT_MODE sortEggsKmaxS $KMAX
done
done


# oneAtATime
# TODO I would like to do this jointly with the oracle if possible
if [[ $DATASET == "propbank" ]]; then
  N=115812
elif [[ $DATASET == "framenet" ]]; then
  N=3500
else
  echo "unknown dataset: $DATASET"
fi
for D in 64 16 4; do
  C=`echo "$N / $D" | bc`
  if [[ $C -lt 100 ]]; then
    echo "data is too small (skipping): $C"
  else
    for OAAT in K F; do
      K="oaat-${OAAT}-D${D}"
      qsub -N $K -o $WD/sge-logs \
        -l "num_proc=1,mem_free=$MEM_FREE,h_rt=240:00:00" \
        ./scripts/precompute-features/train3.sh \
          $WD/$K $FEAT_DIR $DATASET $MEM $JAR_STABLE \
          oneAtATime $OAAT nTrain $C
    done
  fi
done


# Beam size (-DbeamSize)
if [[ $DATASET == "propbank" ]]; then
  N=115812
elif [[ $DATASET == "framenet" ]]; then
  N=3500
else
  echo "unknown dataset: $DATASET"
fi
for D in 64 16 4; do
  C=`echo "$N / $D" | bc`
  if [[ $C -lt 100 ]]; then
    echo "data is too small (skipping): $C"
  else
    for B in 01 02 04 08 16 32; do
      K="beam-${B}-D${D}"
      qsub -N $K -o $WD/sge-logs \
        -l "num_proc=1,mem_free=$MEM_FREE,h_rt=240:00:00" \
        ./scripts/precompute-features/train3.sh \
          $WD/$K $FEAT_DIR $DATASET $MEM $JAR_STABLE \
          beamSize $B nTrain $C
    done
  fi
done


# Feature set (-DfeatureSetFile)
#for D in 640 1280 160 2560; do
for D in 640 1280 320; do
for C in 4 8 16; do
  FD=$FEAT_PARENT/C${C}-D${D}
  if [[ ! -d $FD ]]; then
    echo "missing feature dir: $FD"
  else
    K="features-C${C}-D${D}"
    qsub -N $K -o $WD/sge-logs \
      -l "num_proc=1,mem_free=$MEM_FREE,h_rt=240:00:00" \
      ./scripts/precompute-features/train3.sh \
        $WD/$K $FD $DATASET $MEM $JAR_STABLE
  fi
done
done















IS_PROPBANK="TODO-remove-IS_PROPBANK-in-favor-of-DATASET"
#DEFAULT_DIM=16-160
DEFAULT_DIM=8-640
TAG=""
DEFAULT_ORACLE="RAND_MIN"
DEFAULT_BEAM=1
DEFAULT_NTRAIN=0
DEFAULT_REG=8

# Oracle mode
BEAM_SIZE=$DEFAULT_BEAM
ORACLE_MODE="#"
DIM=$DEFAULT_DIM
FORCE_LEFT_RIGHT="false"
PERCEPTRON="false"
NTRAIN=$DEFAULT_NTRAIN
REG=$DEFAULT_REG
./scripts/precompute-features/train-all.sh \
  $WD/sweep-oracle \
  $DD \
  $IS_PROPBANK \
  $DIM \
  $ORACLE_MODE \
  $BEAM_SIZE \
  $FORCE_LEFT_RIGHT \
  $PERCEPTRON \
  $NTRAIN \
  $REG \
  "${TAG}N"


# Dimension / feature set
BEAM_SIZE=$DEFAULT_BEAM
ORACLE_MODE=$DEFAULT_ORACLE
DIM="#"
FORCE_LEFT_RIGHT="false"
PERCEPTRON="false"
NTRAIN=$DEFAULT_NTRAIN
REG=$DEFAULT_REG
./scripts/precompute-features/train-all.sh \
  $WD/sweep-dim \
  $DD \
  $IS_PROPBANK \
  $DIM \
  $ORACLE_MODE \
  $BEAM_SIZE \
  $FORCE_LEFT_RIGHT \
  $PERCEPTRON \
  $NTRAIN \
  $REG \
  "${TAG}D"

# Force left right
# and perceptron
BEAM_SIZE=$DEFAULT_BEAM
ORACLE_MODE=$DEFAULT_ORACLE
DIM=$DEFAULT_DIM
FORCE_LEFT_RIGHT="#"
PERCEPTRON="#"
NTRAIN=$DEFAULT_NTRAIN
REG=$DEFAULT_REG
./scripts/precompute-features/train-all.sh \
  $WD/sweep-perceptron \
  $DD \
  $IS_PROPBANK \
  $DIM \
  $ORACLE_MODE \
  $BEAM_SIZE \
  $FORCE_LEFT_RIGHT \
  $PERCEPTRON \
  $NTRAIN \
  $REG \
  "${TAG}L"

# Beam size
BEAM_SIZE="#"
ORACLE_MODE=$DEFAULT_ORACLE
DIM=$DEFAULT_DIM
FORCE_LEFT_RIGHT="false"
PERCEPTRON="false"
NTRAIN=$DEFAULT_NTRAIN
REG=$DEFAULT_REG
./scripts/precompute-features/train-all.sh \
  $WD/sweep-beam \
  $DD \
  $IS_PROPBANK \
  $DIM \
  $ORACLE_MODE \
  $BEAM_SIZE \
  $FORCE_LEFT_RIGHT \
  $PERCEPTRON \
  $NTRAIN \
  $REG \
  "${TAG}B"

# Train size
BEAM_SIZE=$DEFAULT_BEAM
ORACLE_MODE=$DEFAULT_ORACLE
DIM=$DEFAULT_DIM
FORCE_LEFT_RIGHT="false"
PERCEPTRON="false"
NTRAIN="#"
REG=$DEFAULT_REG
./scripts/precompute-features/train-all.sh \
  $WD/sweep-size \
  $DD \
  $IS_PROPBANK \
  $DIM \
  $ORACLE_MODE \
  $BEAM_SIZE \
  $FORCE_LEFT_RIGHT \
  $PERCEPTRON \
  $NTRAIN \
  $REG \
  "${TAG}S"

qinfo


# NOTE: No longer needed with perceptron
# # Regularizer strength
# BEAM_SIZE=$DEFAULT_BEAM
# ORACLE_MODE=$DEFAULT_ORACLE
# DIM=$DEFAULT_DIM
# FORCE_LEFT_RIGHT="false"
# PERCEPTRON="false"
# NTRAIN=$DEFAULT_NTRAIN
# REG="#"
# ./scripts/precompute-features/train-all.sh \
#   $WD/sweep-reg \
#   $DD \
#   $IS_PROPBANK \
#   $DIM \
#   $ORACLE_MODE \
#   $BEAM_SIZE \
#   $FORCE_LEFT_RIGHT \
#   $PERCEPTRON \
#   $NTRAIN \
#   $REG \
#   "${TAG}R"

# vim: set syntax=sh
