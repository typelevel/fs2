Following needs to be done before we can merge this new representation into master.
Please remove from the list once done....

## Process.scala

### Process trait

- [x] Process.gatherMap

### Process object

- [x] Process.fill
- [x] Process.iterate
- [x] Process.state
- [x] Process.duration
- [x] Process.every
- [x] Process.sleepUntil
- [x] Process.awakeEvery - fix
- [x] Process.ranges
- [x] Process.supply
- [x] Process.toTask
- [x] Process.forwardFill


### instances

- [x] MonadPlus[Process]


### ProcessSyntax:

- [x] through_y - being removed for now, as it depends on enqueue / connect
- [x] connect - being removed for now
- [x] feed - no longer necessary, as Process is trampolined, this method was

### WriterSyntax
- [x] connectW - being removed for now
- [x] drainW - being removed for now
- [x] connectO - being removed for now
- [x] drainO - being removed for now

### SourceSyntax
- [x] connectTimed
- [x] forwardFill
- [x] toTask

### Process0Syntax
- [x] improve // check toIndexedSeq PC: yep, is using `fast_++`

### Process1Syntax
- [X] Done

### TeeSyntax
- [X] Done

### WyeSyntax
- [ ] detachL -> detach1L
- [ ] detachR -> detach1R

### ChannelSyntax
- [X] *

### SinkTaskSyntax
- [X] *

### EvalProcess
- [x] `eval`
- [x] `gather`

## nio/*.scala
- [X] *

## compress.scala
- [x] *

## Exchange.scala
- [X] mapW
- [X] pipeW
- [X] wye
- [X] readThrough
- [X] Exchange.loopBack

## hash.scala
- [x] *

## io.scala
- [x] *

## package.scala - Semigroup instance
- [x] *

## process1.scala
- [ ] unchunk
- [ ] init
- [ ] liftY
- [ ] record
- [ ] prefixSums
- [ ] suspend1 - not sure if fits in new repre.
- [x] zipWith*

## text.scala
- [x] *

## wye.scala
- [x] current implementation of detachL/R => detach1L/1R

-----

# Specifications

## CompressSpec.scala
- [ ] *

## ExchangeSpec.scala
- [X] *

## HashSpec.scala
- [ ] *

## MergeNSpec.scala
- [ ] fix mergeN drain

## NioSpec.scala
- [ ] connect-server-terminates

## ProcessSpec.scala
- [ ] fill
- [ ] forwardFill
- [ ] enqueue
- [ ] state  - fix
- [ ] pipeIn
- [ ] pipeIn reuse


## Process1Spec.scala
- [ ] unchunk
- [ ] init
- [ ] liftY
- [ ] record
- [ ] prefixSums
- [ ] suspend1 - not sure if fits in new repre.
- [x] zipWith*

## UnsafeChunkRSpec
- [ ] *

## Utf8DecodeSpec
- [ ] *

