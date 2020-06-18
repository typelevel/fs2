package fs2

trait ChunkPlatform[+O] { self: Chunk[O] =>

}

trait ChunkCompanionPlatform { self: Chunk.type =>

}
