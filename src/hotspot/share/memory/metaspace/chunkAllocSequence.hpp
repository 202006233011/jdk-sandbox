/*
 * Copyright (c) 2019, SAP SE. All rights reserved.
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */

#ifndef SHARE_MEMORY_METASPACE_CHUNKALLOCSEQUENCE_HPP
#define SHARE_MEMORY_METASPACE_CHUNKALLOCSEQUENCE_HPP

#include "memory/metaspace.hpp" // For Metaspace::MetaspaceType
#include "memory/metaspace/chunkLevel.hpp"
#include "memory/metaspace/metaspaceEnums.hpp"

namespace metaspace {


// Encodes the chunk progression - very simply, how big chunks are we hand to a class loader.
//
// This is a guessing game - giving it too large chunks may cause memory waste when it stops loading
//  classes; giving it too small chunks may cause fragmentation and unnecessary contention when it
//  calls into Metaspace to get a new chunk.
//

class ChunkAllocSequence {
public:

  virtual chunklevel_t get_next_chunk_level(int num_allocated) const = 0;

  // Given a space type, return the correct allocation sequence to use.
  // The returned object is static and read only.
  static const ChunkAllocSequence* alloc_sequence_by_space_type(MetaspaceType space_type, bool is_class);

};


} // namespace metaspace

#endif // SHARE_MEMORY_METASPACE_CHUNKALLOCSEQUENCE_HPP
