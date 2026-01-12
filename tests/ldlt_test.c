#include "ldlt_rocc.h"
#include <stdint.h>

#define M 4
#define N 4

// Ensure alignment
double A[M*N] __attribute__((aligned(16))) = {
    1.0, 0.0, 0.0, 0.0,
    0.0, 1.0, 0.0, 0.0,
    0.0, 0.0, 1.0, 0.0,
    0.0, 0.0, 0.0, 1.0
};

double b[M] __attribute__((aligned(16))) = {1.0, 2.0, 3.0, 4.0};

// HTIF Support (Minimal)
// HTIF symbols defined in startup.S

int main() {
    // Perform checking command (Funct 1)
    uint64_t status;
    LDLT_CHECK_STATUS(status);

    // Solve
    ldlt_solve(A, b, M, N);
    
    // Check again
    LDLT_CHECK_STATUS(status);
    
    // Termination logic would go here, but return should exit main -> exit -> _exit -> write tohost
    return 0;
}
