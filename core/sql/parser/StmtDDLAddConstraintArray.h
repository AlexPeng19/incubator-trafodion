#ifndef STMTDDLADDCONSTRAINTARRAY_H
#define STMTDDLADDCONSTRAINTARRAY_H
/* -*-C++-*-
 *****************************************************************************
 *
 * File:         StmtDDLAddConstraintArray.h
 * Description:  class for an array of pointers pointing to instances of
 *               class StmtDDLAddConstraint
 *               
 *               
 * Created:      6/21/95
 * Language:     C++
 *
 *
// @@@ START COPYRIGHT @@@
//
// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
//
// @@@ END COPYRIGHT @@@
 *
 *
 *****************************************************************************
 */


#include "Collections.h"
#include "StmtDDLAddConstraint.h"
#ifndef   SQLPARSERGLOBALS_CONTEXT_AND_DIAGS
#define   SQLPARSERGLOBALS_CONTEXT_AND_DIAGS
#endif
#include "SqlParserGlobals.h"

// -----------------------------------------------------------------------
// contents of this file
// -----------------------------------------------------------------------
class StmtDDLAddConstraintArray;

// -----------------------------------------------------------------------
// forward references
// -----------------------------------------------------------------------
// None.

// -----------------------------------------------------------------------
// Definition of class StmtDDLAddConstraintArray
// -----------------------------------------------------------------------
class StmtDDLAddConstraintArray : public LIST(StmtDDLAddConstraint *)
{

public:

  // constructor
  StmtDDLAddConstraintArray(CollHeap *heap = PARSERHEAP())
    : LIST(StmtDDLAddConstraint *)(heap)
  {
  }

  // virtual destructor
  virtual ~StmtDDLAddConstraintArray();

private:

}; // class StmtDDLAddConstraintArray

#endif /* STMTDDLADDCONSTRAINTARRAY_H */
