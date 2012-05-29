package nta.engine.planner.physical;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import nta.catalog.Schema;
import nta.engine.SubqueryContext;
import nta.engine.parser.QueryBlock.SortSpec;
import nta.engine.planner.PlannerUtil;
import nta.engine.planner.logical.JoinNode;
import nta.engine.planner.logical.SortNode;
import nta.engine.utils.TupleUtil;
import nta.storage.FrameTuple;
import nta.storage.Tuple;
import nta.storage.VTuple;

public class MergeJoinExec extends PhysicalExec {
  // from logical plan
  private Schema inSchema;
  private Schema outSchema;

  // sub operations
  private PhysicalExec outer;
  private PhysicalExec inner;

  // temporal tuples and states for nested loop join
  private FrameTuple frameTuple;
  private Tuple outerTuple = null;
  private Tuple innerTuple = null;
  private Tuple outputTuple = null;
  private Tuple outernext = null;

  private final List<Tuple> outertupleSlots;
  private final List<Tuple> innerTupleSlots;
  private Iterator<Tuple> outerIterator;
  private Iterator<Tuple> innerIterator;

  private JoinTupleComparator joincomparator = null;
  private TupleComparator [] tupleComparator = null;

  private final int MAXSIZE = 10000;
  
  private boolean end = false;

  // projection
  private final int[] targetIds;

  public MergeJoinExec(SubqueryContext ctx, JoinNode ann, PhysicalExec outer,
      PhysicalExec inner, SortNode outersort, SortNode innersort) {
    this.outer = outer;
    this.inner = inner;
    this.inSchema = ann.getInputSchema();
    this.outSchema = ann.getOutputSchema();
    this.outertupleSlots = new ArrayList<Tuple>(MAXSIZE);
    this.innerTupleSlots = new ArrayList<Tuple>(MAXSIZE);
    SortSpec[][] sortSpecs = new SortSpec[2][];
    sortSpecs[0] = outersort.getSortKeys();
    sortSpecs[1] = innersort.getSortKeys();
    this.joincomparator = new JoinTupleComparator(outer.getSchema(),
        inner.getSchema(), sortSpecs);
    this.tupleComparator = PlannerUtil.getComparatorsFromJoinQual(
        ann.getJoinQual(), outer.getSchema(), inner.getSchema());
    this.outerIterator = outertupleSlots.iterator();
    this.innerIterator = innerTupleSlots.iterator();
    
    // for projection
    targetIds = TupleUtil.getTargetIds(inSchema, outSchema);

    // for join
    frameTuple = new FrameTuple();
    outputTuple = new VTuple(outSchema.getColumnNum());
  }

  public Tuple next() throws IOException {
    Tuple previous;
    
    if (!outerIterator.hasNext()) {
      if(end == true){
        return null;
      }
      
      if(outerTuple == null){
        outerTuple = outer.next();
      }
      if(innerTuple == null){
        innerTuple = inner.next();
      }
      
      outertupleSlots.clear();
      innerTupleSlots.clear();
      
      int cmp;
      while ((cmp = joincomparator.compare(outerTuple, innerTuple)) != 0) {
        if (cmp > 0) {
          innerTuple = inner.next();
        } else if (cmp < 0) {
          outerTuple = outer.next();
        }
        if (innerTuple == null || outerTuple == null) {
          return null;
        }
      }
      
      previous = outerTuple;
      do {
        outertupleSlots.add(outerTuple);
        outerTuple = outer.next();
        if (outerTuple == null) {
          end = true;
          break;
        }
      } while (tupleComparator[0].compare(previous, outerTuple) == 0);
      outerIterator = outertupleSlots.iterator();
      outernext = outerIterator.next();
      
      previous = innerTuple;
      do {
        innerTupleSlots.add(innerTuple);
        innerTuple = inner.next();
        if (innerTuple == null) {
          end = true;
          break;
        }
      } while (tupleComparator[1].compare(previous, innerTuple) == 0);
      innerIterator = innerTupleSlots.iterator();
    }
    
    if(!innerIterator.hasNext()){
      outernext = outerIterator.next();
      innerIterator = innerTupleSlots.iterator();
    }
    
    frameTuple.set(outernext, innerIterator.next());
    TupleUtil.project(frameTuple, outputTuple, targetIds);
    return outputTuple;
  }

  @Override
  public Schema getSchema() {
    return outSchema;
  }

  @Override
  public void rescan() throws IOException {
    outer.rescan();
    inner.rescan();
    outertupleSlots.clear();
    innerTupleSlots.clear();
    outerIterator = outertupleSlots.iterator();
    innerIterator = innerTupleSlots.iterator();
  }
}