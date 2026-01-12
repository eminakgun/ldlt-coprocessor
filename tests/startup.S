.section .text.init
.global _start
_start:
    # Initialize Stack Pointer
    # _end is defined in linker script (end of BSS)
    # We add 64KB for stack
    la sp, _end + 0x10000
    
    # Enable FPU (Status.FS = Initial) if FPU present (LDLT uses double)
    li t0, 0x6000
    csrs mstatus, t0
    
    # Jump to main
    call main

    # Exit (Write 1 to tohost)
    la t0, tohost
    li t1, 1
    sd t1, 0(t0)

loop:
    j loop

.section .htif
.align 6
.global tohost
tohost: .dword 0
.global fromhost
fromhost: .dword 0
