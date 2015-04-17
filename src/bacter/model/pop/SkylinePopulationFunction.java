/*
 * Copyright (C) 2015 Tim Vaughan <tgvaughan@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package bacter.model.pop;

import bacter.CFEventList;
import bacter.CFEventList.Event;
import bacter.ConversionGraph;
import beast.core.Input;
import beast.core.Loggable;
import beast.core.parameter.IntegerParameter;
import beast.core.parameter.RealParameter;
import beast.evolution.tree.Tree;
import beast.evolution.tree.coalescent.PopulationFunction;

import java.io.PrintStream;
import java.util.*;

/**
 * In BEAST 2, BSP is implemented as a tree distribution rather than
 * a population function.  The population function approach is much
 * more flexible and is directly applicable to BACTER's model.
 *
 * @author Tim Vaughan <tgvaughan@gmail.com>
 */
public class SkylinePopulationFunction extends PopulationFunction.Abstract implements Loggable {

    public Input<ConversionGraph> acgInput = new Input<>("acg",
            "Conversion graph", Input.Validate.REQUIRED);

    public Input<RealParameter> popSizesInput = new Input<>("popSizes",
            "Population sizes in intervals", Input.Validate.REQUIRED);

    public Input<IntegerParameter> groupSizesInput = new Input<>("groupSizes",
            "Group size parameter.", Input.Validate.REQUIRED);

    public Input<Boolean> initGroupSizesInput = new Input<>("initGroupSizes",
            "If true (default), initialize group sizes parameter.", true);

    ConversionGraph acg;
    RealParameter popSizes;
    IntegerParameter groupSizes;

    private boolean dirty;

    double[] intensities;
    double[] groupBoundaries;

    @Override
    public void initAndValidate() throws Exception {
        super.initAndValidate();

        acg = acgInput.get();
        popSizes = popSizesInput.get();
        groupSizes = groupSizesInput.get();

        // Initialize groupSizes to something sensible.
        if (initGroupSizesInput.get()) {
            int nCoalEvents = acg.getInternalNodeCount();
            Integer[] values = new Integer[groupSizes.getDimension()];
            int cumulant = 0;
            for (int i=0; i<values.length; i++) {
                values[i] = nCoalEvents/values.length;
                cumulant += values[i];
            }
            values[values.length-1] += nCoalEvents - cumulant;

            IntegerParameter newParam = new IntegerParameter(values);
            groupSizes.assignFromWithoutID(newParam);
        }

        dirty = true;
    }

    @Override
    public List<String> getParameterIds() {
        List<String> ids = new ArrayList<>();
        ids.add(acgInput.get().getID());
        ids.add(popSizesInput.get().getID());
        ids.add(groupSizesInput.get().getID());

        return ids;
    }

    @Override
    public void prepare() {

        if (!dirty)
            return;

        List<Event> cfEvents = acg.getCFEvents();
        groupBoundaries = new double[groupSizes.getDimension() - 1];
        int lastEventIdx = -1;
        for (int i=0; i<groupBoundaries.length; i++) {
            int cumulant = 0;
            do {
                lastEventIdx += 1;
                if (cfEvents.get(lastEventIdx).getType() == CFEventList.EventType.COALESCENCE)
                    cumulant += 1;
            } while (cumulant < groupSizes.getValue(i));

            groupBoundaries[i] = cfEvents.get(lastEventIdx).getHeight();
        }

        intensities = new double[groupSizes.getDimension()+1];
        intensities[0] = 0.0;
        double lastBoundary = 0.0;

        for (int i=1; i<intensities.length-1; i++) {
            intensities[i] = intensities[i-1]
                    + (groupBoundaries[i-1]-lastBoundary)/popSizes.getValue(i-1);
        }

        intensities[intensities.length-1] = (acg.getRoot().getHeight()-lastBoundary)
                /popSizes.getValue(popSizes.getDimension()-1);

        System.out.println(acg.toXML());
        dirty = false;
    }

    @Override
    protected boolean requiresRecalculation() {
        dirty = true;
        return true;
    }

    @Override
    protected void store() {
        dirty = true;
        super.store();
    }

    @Override
    protected void restore() {
        dirty = true;
        super.restore();
    }

    @Override
    public double getPopSize(double t) {
        prepare();

        if (t < 0)
            return getPopSize(0);

        int interval = Arrays.binarySearch(groupBoundaries, t);

        if (interval<0)
            interval = -(interval + 1) - 1;  // boundary to the left of time.

        return popSizes.getValue(interval+1);
    }

    @Override
    public double getIntensity(double t) {
        prepare();

        if (t < 0 )
            return -t/popSizes.getValue(0);

        int interval = Arrays.binarySearch(groupBoundaries, t);

        if (interval<0)
            interval = -(interval + 1) - 1; // boundary to the left of time.

        double intensityGridTime = interval >= 0
                ? groupBoundaries[interval]
                : 0.0;

        return intensities[interval+1] + (t-intensityGridTime)/popSizes.getValue(interval+1);
    }

    @Override
    public double getInverseIntensity(double x) {
        throw new RuntimeException("Method not implemented");
    }

    // Loggable implementation:

    @Override
    public void init(PrintStream out) throws Exception {
        prepare();

        for (int i=0; i<popSizes.getDimension(); i++) {
            out.print(getID() + ".N" + i + "\t");
            if (i>0)
                out.print(getID() + ".t" + (i-1) + "\t");
        }
    }

    @Override
    public void log(int nSample, PrintStream out) {
        prepare();

        for (int i=0; i<popSizes.getDimension(); i++) {
            out.print(popSizes.getValue(i) + "\t");
            if (i>0)
                out.print(groupBoundaries[i-1] + "\t");
        }
    }

    @Override
    public void close(PrintStream out) {

    }

    /**
     * Main method for testing.
     *
     * @param args command line arguments (unused)
     */
    public static void main(String[] args) throws Exception {

        String acgString = "[&15,0,1.3905355989030808,31,770,1.597708055397074] " +
                        "[&30,931,2.4351280458424904,36,2486,3.78055549386568] " +
                        "[&15,941,2.0439957300083322,38,2364,6.911056700367016] " +
                        "[&36,1091,4.285505683622974,38,2589,9.867725913197855] " +
                        "((((10:0.5385300170206817,(17:0.116794353049212," +
                        "((3:0.039229346597297564,12:0.039229346597297564)23:0.04582913870888949," +
                        "13:0.08505848530618705)24:0.03173586774302495)26:0.4217356639714697)28:1.8114199763246093," +
                        "((8:0.10883006062265468,2:0.10883006062265468)25:0.556428062025291," +
                        "(6:0.5393311342677402,11:0.5393311342677402)29:0.12592698838020555)31:1.6846918706973453)34:1.4536824928125807," +
                        "(1:0.47184545557390367,14:0.47184545557390367)27:3.331787030583968)37:2.9704369411362554," +
                        "(((15:2.0624287390593707,((16:0.01825347077733299,19:0.01825347077733299)21:0.7668749128372041," +
                        "(7:0.008018731329538273,9:0.008018731329538273)20:0.7771096522849988)32:1.2773003554448337)33:0.7487092404613747," +
                        "4:2.8111379795207454)35:0.1331794525400949,((0:0.0243537216663141," +
                        "5:0.0243537216663141)22:0.5681537100482162,18:0.5925074317145304)30:2.35181000034631)36:3.829751995233287)38:0.0";

        ConversionGraph acg = new ConversionGraph();
        acg.initByName("sequenceLength", 10000, "fromString", acgString);


        SkylinePopulationFunction skyline = new SkylinePopulationFunction();
        skyline.initByName(
                "acg", acg,
                "popSizes", new RealParameter("5.0 1.0 5.0 1.0"),
                "groupSizes", new IntegerParameter("0 0 0 0"));

        try (PrintStream ps = new PrintStream("out.txt")){
            ps.println("t N intensity");
            double t = 0.0;
            while (t<10) {
                ps.format("%g %g %g\n", t,
                        skyline.getPopSize(t), skyline.getIntensity(t));
                t += 0.01;
            }
            ps.close();
        }
    }
}
