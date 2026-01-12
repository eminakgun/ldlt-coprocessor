package chipyard

import org.chipsalliance.cde.config.{Config, Parameters}
import freechips.rocketchip.tile.{BuildRoCC, OpcodeSet}
import freechips.rocketchip.diplomacy.LazyModule

class WithLDLTAccelerator extends Config((site, here, up) => {
  case BuildRoCC => up(BuildRoCC) ++ Seq((p: Parameters) => {
    val ldlt = LazyModule(new LDLTAccelerator(OpcodeSet.custom0)(p))
    ldlt
  })
})

class LDLTSystemConfig extends Config(
  new WithLDLTAccelerator ++
  new freechips.rocketchip.rocket.WithNHugeCores(1) ++
  new chipyard.config.AbstractConfig)
