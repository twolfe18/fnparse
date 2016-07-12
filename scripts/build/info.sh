
F=src/main/resources/build-info.txt

echo "Built from commit: `git rev-parse HEAD`" >$F
echo "With the following modifications:" >>$F
git diff --patch >>$F

