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
- [x] detachL -> detach1L
- [x] detachR -> detach1R

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
- [x] unchunk
- [x] init - this has been removed, renamed shiftRight
- [x] liftY
- [x] record - removing this for now, not really idiomatic or safe
- [x] prefixSums
- [x] suspend1 - not sure if fits in new repre. PC: not needed, just use Process.suspend instead
- [x] zipWith*

## text.scala
- [x] *

## wye.scala
- [x] current implementation of detachL/R => detach1L/1R

-----

# Specifications

## CompressSpec.scala
- [x] *

## ExchangeSpec.scala
- [X] *

## HashSpec.scala
- [x] *

## MergeNSpec.scala
- [x] fix mergeN drain

## NioSpec.scala
- [x] connect-server-terminates

## ProcessSpec.scala
- [x] fill
- [x] forwardFill
- [x] enqueue - being removed
- [ ] state  - fix PC - not sure what this is about?
- [x] pipeIn
- [x] pipeIn reuse


## Process1Spec.scala
- [x] unchunk
- [x] init - replaced by shiftRight
- [x] liftY
- [x] record - has been removed
- [x] prefixSums
- [x] suspend1 - not sure if fits in new repre. (replaced by suspend)
- [x] zipWith*

## UnsafeChunkRSpec
- [x] *

## Utf8DecodeSpec
- [x] *

