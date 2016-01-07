
echo "make sure you've built a jar in target/ first!"

if [[ $# != 1 ]]; then
  echo "please provide a directory for log files"
  exit 1
fi

# Baseline
qsub -o $1 ./fmodel-tests.sh

# ablate one
qsub -o $1 ./fmodel-tests.sh featProdBase false
qsub -o $1 ./fmodel-tests.sh featProdF false
qsub -o $1 ./fmodel-tests.sh featProdFK false
qsub -o $1 ./fmodel-tests.sh featProdK false

qsub -o $1 ./fmodel-tests.sh sortEggsMode NONE  # default is BY_KS

qsub -o $1 ./fmodel-tests.sh oneAtATime 0 # T
qsub -o $1 ./fmodel-tests.sh oneAtATime 1 # F
qsub -o $1 ./fmodel-tests.sh oneAtATime 2 # K
qsub -o $1 ./fmodel-tests.sh oneAtATime 3 # S

# ablate one
qsub -o $1 ./fmodel-tests.sh ANY_GLOBALS false ARG_LOC_TA true ARG_LOC_TA_TA false ARG_LOC_TA_TA_F true ARG_LOC_TA_TA_FK true ARG_LOC_TA_TA_K true
qsub -o $1 ./fmodel-tests.sh ANY_GLOBALS false ARG_LOC_TA true ARG_LOC_TA_TA true ARG_LOC_TA_TA_F false ARG_LOC_TA_TA_FK true ARG_LOC_TA_TA_K true
qsub -o $1 ./fmodel-tests.sh ANY_GLOBALS false ARG_LOC_TA true ARG_LOC_TA_TA true ARG_LOC_TA_TA_F true ARG_LOC_TA_TA_FK false ARG_LOC_TA_TA_K true
qsub -o $1 ./fmodel-tests.sh ANY_GLOBALS false ARG_LOC_TA true ARG_LOC_TA_TA true ARG_LOC_TA_TA_F true ARG_LOC_TA_TA_FK true ARG_LOC_TA_TA_K false

# ablate one
qsub -o $1 ./fmodel-tests.sh ANY_GLOBALS false ARG_LOC_AA true ARG_LOC_AA_TA false ARG_LOC_AA_TA_F true ARG_LOC_AA_TA_FK true ARG_LOC_AA_TA_K true
qsub -o $1 ./fmodel-tests.sh ANY_GLOBALS false ARG_LOC_AA true ARG_LOC_AA_TA true ARG_LOC_AA_TA_F false ARG_LOC_AA_TA_FK true ARG_LOC_AA_TA_K true
qsub -o $1 ./fmodel-tests.sh ANY_GLOBALS false ARG_LOC_AA true ARG_LOC_AA_TA true ARG_LOC_AA_TA_F true ARG_LOC_AA_TA_FK false ARG_LOC_AA_TA_K true
qsub -o $1 ./fmodel-tests.sh ANY_GLOBALS false ARG_LOC_AA true ARG_LOC_AA_TA true ARG_LOC_AA_TA_F true ARG_LOC_AA_TA_FK true ARG_LOC_AA_TA_K false

# ablate one
qsub -o $1 ./fmodel-tests.sh ANY_GLOBALS false ROLE_COOC_TA true ROLE_COOC_TA false ROLE_COOC_TA_F true ROLE_COOC_TA_FK true ROLE_COOC_TA_K true
qsub -o $1 ./fmodel-tests.sh ANY_GLOBALS false ROLE_COOC_TA true ROLE_COOC_TA true ROLE_COOC_TA_F false ROLE_COOC_TA_FK true ROLE_COOC_TA_K true
qsub -o $1 ./fmodel-tests.sh ANY_GLOBALS false ROLE_COOC_TA true ROLE_COOC_TA true ROLE_COOC_TA_F true ROLE_COOC_TA_FK false ROLE_COOC_TA_K true
qsub -o $1 ./fmodel-tests.sh ANY_GLOBALS false ROLE_COOC_TA true ROLE_COOC_TA true ROLE_COOC_TA_F true ROLE_COOC_TA_FK true ROLE_COOC_TA_K false

# ablate one
qsub -o $1 ./fmodel-tests.sh ANY_GLOBALS false NUM_ARGS true NUM_ARGS false NUM_ARGS_F true NUM_ARGS_FK true NUM_ARGS_K true
qsub -o $1 ./fmodel-tests.sh ANY_GLOBALS false NUM_ARGS true NUM_ARGS true NUM_ARGS_F false NUM_ARGS_FK true NUM_ARGS_K true
qsub -o $1 ./fmodel-tests.sh ANY_GLOBALS false NUM_ARGS true NUM_ARGS true NUM_ARGS_F true NUM_ARGS_FK false NUM_ARGS_K true
qsub -o $1 ./fmodel-tests.sh ANY_GLOBALS false NUM_ARGS true NUM_ARGS true NUM_ARGS_F true NUM_ARGS_FK true NUM_ARGS_K false

qsub -o $1 ./fmodel-tests.sh beamSize 2
qsub -o $1 ./fmodel-tests.sh beamSize 4
qsub -o $1 ./fmodel-tests.sh beamSize 8
qsub -o $1 ./fmodel-tests.sh beamSize 16
qsub -o $1 ./fmodel-tests.sh beamSize 32

qsub -o $1 ./fmodel-tests.sh featureSet /home/hltcoe/twolfe/fnparse/scripts/having-a-laugh/propbank-8-640.fs
#qsub -o $1 ./fmodel-tests.sh featureSet /home/hltcoe/twolfe/fnparse/scripts/having-a-laugh/propbank-16-640.fs
qsub -o $1 ./fmodel-tests.sh featureSet /home/hltcoe/twolfe/fnparse/scripts/having-a-laugh/propbank-32-640.fs
qsub -o $1 ./fmodel-tests.sh featureSet /home/hltcoe/twolfe/fnparse/scripts/having-a-laugh/propbank-64-640.fs
qsub -o $1 ./fmodel-tests.sh featureSet /home/hltcoe/twolfe/fnparse/scripts/having-a-laugh/propbank-mix-640.fs

qsub -o $1 ./fmodel-tests.sh featureSet /home/hltcoe/twolfe/fnparse/scripts/having-a-laugh/propbank-8-160.fs
qsub -o $1 ./fmodel-tests.sh featureSet /home/hltcoe/twolfe/fnparse/scripts/having-a-laugh/propbank-16-160.fs
qsub -o $1 ./fmodel-tests.sh featureSet /home/hltcoe/twolfe/fnparse/scripts/having-a-laugh/propbank-32-160.fs
qsub -o $1 ./fmodel-tests.sh featureSet /home/hltcoe/twolfe/fnparse/scripts/having-a-laugh/propbank-64-160.fs
qsub -o $1 ./fmodel-tests.sh featureSet /home/hltcoe/twolfe/fnparse/scripts/having-a-laugh/propbank-mix-160.fs

qinfo

