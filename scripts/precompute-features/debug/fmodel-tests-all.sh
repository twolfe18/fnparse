
echo "make sure you've built a jar in target/ first!"

if [[ $# != 1 ]]; then
  echo "please provide a directory for log files"
  exit 1
fi

cd scripts/precompute-features/debug

# Baseline
qsub -N baseline -o $1 ./fmodel-tests.sh

# ablate one
qsub -N ablate1-base -o $1 ./fmodel-tests.sh featProdBase false
qsub -N ablate1-f -o $1 ./fmodel-tests.sh featProdF false
qsub -N ablate1-fk -o $1 ./fmodel-tests.sh featProdFK false
qsub -N ablate1-k -o $1 ./fmodel-tests.sh featProdK false

qsub -N noEggSort -o $1 ./fmodel-tests.sh sortEggsMode NONE  # default is BY_KS

#f=/export/projects/twolfe/fnparse-output/experiments/debug/jan6a
#qsub -o $1 ./fmodel-tests.sh oracleMode RAND
qsub -N oracle-randMin -o $1 ./fmodel-tests.sh oracleMode RAND_MIN
qsub -N oracle-randMax -o $1 ./fmodel-tests.sh oracleMode RAND_MAX
qsub -N oracle-min -o $1 ./fmodel-tests.sh oracleMode MIN
qsub -N oracle-max -o $1 ./fmodel-tests.sh oracleMode MAX

qsub -N oaat-T -o $1 ./fmodel-tests.sh oneAtATime 0 # T
qsub -N oaat-F -o $1 ./fmodel-tests.sh oneAtATime 1 # F
qsub -N oaat-K -o $1 ./fmodel-tests.sh oneAtATime 2 # K
qsub -N oaat-S -o $1 ./fmodel-tests.sh oneAtATime 3 # S

#for m in RAND MIN RAND_MIN RAND_MAX MAX; do
for m in RAND MIN; do
  for b in 2 4 8 16 32 64; do
    qsub -N beam$b-oracle$m -o $1 ./fmodel-tests.sh beamSize $b oracleMode $m
  done
done

# ablate one
qsub -N tune-argLoc1 -o $1 ./fmodel-tests.sh ANY_GLOBALS false ARG_LOC true ARG_LOC_TA_TA false ARG_LOC_TA_TA_F true ARG_LOC_TA_TA_FK true ARG_LOC_TA_TA_K true
qsub -N tune-argLoc1 -o $1 ./fmodel-tests.sh ANY_GLOBALS false ARG_LOC true ARG_LOC_TA_TA true ARG_LOC_TA_TA_F false ARG_LOC_TA_TA_FK true ARG_LOC_TA_TA_K true
qsub -N tune-argLoc1 -o $1 ./fmodel-tests.sh ANY_GLOBALS false ARG_LOC true ARG_LOC_TA_TA true ARG_LOC_TA_TA_F true ARG_LOC_TA_TA_FK false ARG_LOC_TA_TA_K true
qsub -N tune-argLoc1 -o $1 ./fmodel-tests.sh ANY_GLOBALS false ARG_LOC true ARG_LOC_TA_TA true ARG_LOC_TA_TA_F true ARG_LOC_TA_TA_FK true ARG_LOC_TA_TA_K false

# ablate one
qsub -N tune-argLoc2 -o $1 ./fmodel-tests.sh ANY_GLOBALS false ARG_LOC true ARG_LOC_AA_TA false ARG_LOC_AA_TA_F true ARG_LOC_AA_TA_FK true ARG_LOC_AA_TA_K true
qsub -N tune-argLoc2 -o $1 ./fmodel-tests.sh ANY_GLOBALS false ARG_LOC true ARG_LOC_AA_TA true ARG_LOC_AA_TA_F false ARG_LOC_AA_TA_FK true ARG_LOC_AA_TA_K true
qsub -N tune-argLoc2 -o $1 ./fmodel-tests.sh ANY_GLOBALS false ARG_LOC true ARG_LOC_AA_TA true ARG_LOC_AA_TA_F true ARG_LOC_AA_TA_FK false ARG_LOC_AA_TA_K true
qsub -N tune-argLoc2 -o $1 ./fmodel-tests.sh ANY_GLOBALS false ARG_LOC true ARG_LOC_AA_TA true ARG_LOC_AA_TA_F true ARG_LOC_AA_TA_FK true ARG_LOC_AA_TA_K false

# ablate one
qsub -N tune-roleCooc -o $1 ./fmodel-tests.sh ANY_GLOBALS false ROLE_COOC true ROLE_COOC_TA false ROLE_COOC_TA_F true ROLE_COOC_TA_FK true ROLE_COOC_TA_K true
qsub -N tune-roleCooc -o $1 ./fmodel-tests.sh ANY_GLOBALS false ROLE_COOC true ROLE_COOC_TA true ROLE_COOC_TA_F false ROLE_COOC_TA_FK true ROLE_COOC_TA_K true
qsub -N tune-roleCooc -o $1 ./fmodel-tests.sh ANY_GLOBALS false ROLE_COOC true ROLE_COOC_TA true ROLE_COOC_TA_F true ROLE_COOC_TA_FK false ROLE_COOC_TA_K true
qsub -N tune-roleCooc -o $1 ./fmodel-tests.sh ANY_GLOBALS false ROLE_COOC true ROLE_COOC_TA true ROLE_COOC_TA_F true ROLE_COOC_TA_FK true ROLE_COOC_TA_K false

# ablate one
qsub -N tune-numArgs -o $1 ./fmodel-tests.sh ANY_GLOBALS false NUM_ARGS true NUM_ARGS_TA false NUM_ARGS_TA_F true NUM_ARGS_TA_FK true NUM_ARGS_TA_K true
qsub -N tune-numArgs -o $1 ./fmodel-tests.sh ANY_GLOBALS false NUM_ARGS true NUM_ARGS_TA true NUM_ARGS_TA_F false NUM_ARGS_TA_FK true NUM_ARGS_TA_K true
qsub -N tune-numArgs -o $1 ./fmodel-tests.sh ANY_GLOBALS false NUM_ARGS true NUM_ARGS_TA true NUM_ARGS_TA_F true NUM_ARGS_TA_FK false NUM_ARGS_TA_K true
qsub -N tune-numArgs -o $1 ./fmodel-tests.sh ANY_GLOBALS false NUM_ARGS true NUM_ARGS_TA true NUM_ARGS_TA_F true NUM_ARGS_TA_FK true NUM_ARGS_TA_K false

for m in RAND_MIN RAND_MAX MAX; do
  for b in 2 4 8 16 32 64; do
    qsub -N beam$b-oracle$m -o $1 ./fmodel-tests.sh beamSize $b oracleMode $m
  done
done

qsub -N tune-FS-640 -o $1 ./fmodel-tests.sh featureSet /home/hltcoe/twolfe/fnparse/scripts/having-a-laugh/propbank-4-640.fs
qsub -N tune-FS-640 -o $1 ./fmodel-tests.sh featureSet /home/hltcoe/twolfe/fnparse/scripts/having-a-laugh/propbank-8-640.fs
qsub -N tune-FS-640 -o $1 ./fmodel-tests.sh featureSet /home/hltcoe/twolfe/fnparse/scripts/having-a-laugh/propbank-16-640.fs
qsub -N tune-FS-640 -o $1 ./fmodel-tests.sh featureSet /home/hltcoe/twolfe/fnparse/scripts/having-a-laugh/propbank-32-640.fs
#qsub -N tune-FS-640 -o $1 ./fmodel-tests.sh featureSet /home/hltcoe/twolfe/fnparse/scripts/having-a-laugh/propbank-64-640.fs
qsub -N tune-FS-640 -o $1 ./fmodel-tests.sh featureSet /home/hltcoe/twolfe/fnparse/scripts/having-a-laugh/propbank-mix-640.fs

qsub -N tune-FS-160 -o $1 ./fmodel-tests.sh featureSet /home/hltcoe/twolfe/fnparse/scripts/having-a-laugh/propbank-4-160.fs
qsub -N tune-FS-160 -o $1 ./fmodel-tests.sh featureSet /home/hltcoe/twolfe/fnparse/scripts/having-a-laugh/propbank-8-160.fs
qsub -N tune-FS-160 -o $1 ./fmodel-tests.sh featureSet /home/hltcoe/twolfe/fnparse/scripts/having-a-laugh/propbank-16-160.fs
qsub -N tune-FS-160 -o $1 ./fmodel-tests.sh featureSet /home/hltcoe/twolfe/fnparse/scripts/having-a-laugh/propbank-32-160.fs
#qsub -N tune-FS-160 -o $1 ./fmodel-tests.sh featureSet /home/hltcoe/twolfe/fnparse/scripts/having-a-laugh/propbank-64-160.fs
qsub -N tune-FS-160 -o $1 ./fmodel-tests.sh featureSet /home/hltcoe/twolfe/fnparse/scripts/having-a-laugh/propbank-mix-160.fs


qinfo

