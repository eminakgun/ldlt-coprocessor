#ifndef LDLT_ROCC_H
#define LDLT_ROCC_H

#include "rocc.h"

#define LDLT_OPCODE 0

// Funct codes
#define FUNC_SOLVE   0
#define FUNC_CHECK   1

#define LDLT_INVOKE_SOLVE(dst, desc_ptr) \
    ROCC_INSTRUCTION_R_R_I(LDLT_OPCODE, dst, desc_ptr, 0, FUNC_SOLVE, 10, 11)

#define LDLT_CHECK_STATUS(dst) \
    ROCC_INSTRUCTION_R_R_I(LDLT_OPCODE, dst, 0, 0, FUNC_CHECK, 10, 11)

typedef struct {
    double* A_ptr;
    double* b_ptr;
    uint64_t m;
    uint64_t n;
    uint64_t flags;
} LDLT_Descriptor;

static inline void ldlt_solve(double* A, double* b, uint64_t m, uint64_t n) {
    LDLT_Descriptor desc;
    desc.A_ptr = A;
    desc.b_ptr = b;
    desc.m = m;
    desc.n = n;
    desc.flags = 0;

    uint64_t status;
    
    asm volatile ("fence"); 
    
    LDLT_INVOKE_SOLVE(status, &desc);
    
    do {
        LDLT_CHECK_STATUS(status);
    } while (status != 0);
    
    asm volatile ("fence");
}

#endif
