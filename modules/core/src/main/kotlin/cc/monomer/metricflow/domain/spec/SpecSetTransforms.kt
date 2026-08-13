package cc.monomer.metricflow.domain.spec

/**
 * Transform that extracts every element name from every spec in the set.
 *
 * Port of `metricflow_semantics.specs.spec_set_transforms.ToElementNameSet`.
 *
 * Used for emitting an "all referenced columns" set, e.g. to drive column-
 * pruning in the SQL optimizer.
 */
object ToElementNameSet : InstanceSpecSetTransform<Set<String>> {
    override fun transform(specSet: InstanceSpecSet): Set<String> {
        val out = LinkedHashSet<String>()
        for (s in specSet.metricSpecs) out.add(s.elementName)
        for (s in specSet.simpleMetricInputSpecs) out.add(s.elementName)
        for (s in specSet.dimensionSpecs) out.add(s.elementName)
        for (s in specSet.timeDimensionSpecs) out.add(s.elementName)
        for (s in specSet.entitySpecs) out.add(s.elementName)
        for (s in specSet.groupByMetricSpecs) out.add(s.elementName)
        return out
    }
}
