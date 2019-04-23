/*
Copyright 2019 National Technology & Engineering Solutions of Sandia, LLC (NTESS).
Under the terms of Contract DE-NA0003525 with NTESS,
the U.S. Government retains certain rights in this software.
*/

package gov.sandia.n2a.ui.eq;

import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.CubicCurve2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;

public class GraphEdge
{
    protected GraphNode nodeFrom;  // The connection
    protected GraphNode nodeTo;    // The endpoint that this edge goes to
    protected GraphEdge edgeOther; // For binary connections only, the edge to the other endpoint. Used to coordinate a smooth curve through the connection node.
    protected String    fromName;  // Name of endpoint variable in connection part.

    protected Shape     shape;
    // TODO: describe how to draw fromName, and include it in the bounds
    protected Rectangle bounds;

    public GraphEdge (GraphNode nodeFrom, GraphNode nodeTo, String fromName)
    {
        this.nodeFrom = nodeFrom;
        this.nodeTo   = nodeTo;
        this.fromName = fromName;
    }

    /**
        Updates our cached shape information based current state of graph nodes.
        @param from The graph node that called this function. Helps determine whether to update shared parameters.
    **/
    public void updateShape (GraphNode caller)
    {
        Rectangle Cbounds = nodeFrom.getBounds ();
        Vector2 c = new Vector2 (Cbounds.getCenterX (), Cbounds.getCenterY ());

        Rectangle Abounds = null;
        Vector2   a       = null;
        if (nodeTo != null)
        {
            Abounds = nodeTo.getBounds ();
            a       = new Vector2 (Abounds.getCenterX (), Abounds.getCenterY ());
        }

        double sign = nodeFrom.edgesOut.get (0) == this ? -1 : 1;  // Direction along c2c that this edge should follow.

        // If needed, update shared parameters of a binary connection
        if (edgeOther != null  &&  (caller == nodeTo  ||  caller == nodeFrom  &&  sign < 0))
        {
            Rectangle Bbounds = edgeOther.nodeTo.getBounds ();
            Vector2 b = new Vector2 (Bbounds.getCenterX (), Bbounds.getCenterY ());

            Vector2 ab = a.add (b).multiply (0.5);  // average position
            Vector2 c2c = c.subtract (ab);
            double c2cLength = c2c.length ();
            double abLength = a.subtract (b).length ();
            c2c = c2c.normalize ();
            nodeFrom.a2b = new Vector2 (-c2c.y, c2c.x);
            Vector2 ab2b = b.subtract (ab);

            // Ensure that a2b points from A to B (in source part), not the other way.
            // Two things could flip:
            // 1) Whether the node claimed to be "A" is actually A. (sign=-1 means A is A; sign=1 means A is really B)
            // 2) Whether the computed vector actually goes from A to B. (dot product is positive if A->B, and negatives if B->A)
            if (sign * ab2b.dot (nodeFrom.a2b) > 0) nodeFrom.a2b = nodeFrom.a2b.multiply (-1);

            if (c2cLength > abLength) nodeFrom.c2c = c2c;
            else                      nodeFrom.c2c = null; // Force endpoint drawing to use direct path rather than parallel path.

            // If needed, update shape parameters of the other edge.
            if (caller == nodeTo) edgeOther.updateShape (caller);
        }

        FontMetrics fm = nodeFrom.getFontMetrics (nodeFrom.getFont ());
        double em = Math.max (fm.getHeight (), fm.getMaxAdvance ());

        // parameters for endArrow
        Vector2 tip = null;
        double angle = 0;

        if (nodeTo == null)  // Unconnected endpoint
        {
            if (nodeFrom.edgesOut.size () <= 2)
            {
                // Use sign to point out left or right side of node.
                double w = Cbounds.getWidth () + 2 * em;
                tip = new Vector2 (c.x + w * sign, c.y);
            }
            else
            {
                // Distribute rays around node. This method is limited to 8 positions,
                // but no sane person would have more than an 8-way connection.
                double w = Cbounds.getWidth ()  + 2 * em;
                double h = Cbounds.getHeight () + 2 * em;
                int index = nodeFrom.edgesOut.indexOf (this);
                switch (index)
                {
                    case 0: tip = new Vector2 (c.x - w, c.y    ); break;
                    case 1: tip = new Vector2 (c.x + w, c.y    ); break;
                    case 2: tip = new Vector2 (c.x    , c.y + h); break;
                    case 3: tip = new Vector2 (c.x    , c.y - h); break;
                    case 4: tip = new Vector2 (c.x - w, c.y + h); break;
                    case 5: tip = new Vector2 (c.x + w, c.y + h); break;
                    case 6: tip = new Vector2 (c.x - w, c.y - h); break;
                    case 7: tip = new Vector2 (c.x + w, c.y - h); break;
                }
            }
            shape = new Line2D.Double (c.x, c.y, tip.x, tip.y);
            Vector2 ca = tip.subtract (c);
            angle = Math.atan2 (ca.y, ca.x);
        }
        else if (nodeFrom.a2b == null)  // Any other connection type besides binary (unary, ternary, ...). Uses straight edges.
        {
            Segment2 s = new Segment2 (a, c);
            tip = intersection (s, Abounds);
            if (tip == null)  // o can be null if c is inside Abounds, and therefore no edge is visible
            {
                shape  = null;
                bounds = null;
                return;
            }
            else
            {
                shape = new Line2D.Double (c.x, c.y, tip.x, tip.y);
                angle = s.angle ();
            }
        }
        else  // Part of a binary connection
        {
            double length = Abounds.getWidth () + Abounds.getHeight ();  // far enough to get from center of endpoint to outside of bounds
            Vector2 C2;  // unit vector in direction of endpoint -> connection
            if (nodeFrom.c2c == null) C2 = c.subtract (a).normalize ();  // direct path
            else                      C2 = nodeFrom.c2c;  // parallel path
            Vector2 o = a.add (C2.multiply (length));  // "outside" point in the direction of the connection
            Segment2 s = new Segment2 (a, o);  // segment from center of endpoint to outside point
            tip = intersection (s, Abounds);  // point on the periphery of the endpoint
            length = c.distance (tip) / 3;
            Vector2 w1 = tip.add (C2.multiply (length));
            Vector2 w2 = c.add (nodeFrom.a2b.multiply (sign * length));
            shape = new CubicCurve2D.Double (tip.x, tip.y, w1.x, w1.y, w2.x, w2.y, c.x, c.y);
            angle = s.angle ();
        }

        // Arrow head
        Path2D path = new Path2D.Double (shape);
        double da = Math.PI / 6;  // constant describing spread of arrow head
        Vector2 end = new Vector2 (tip, angle + da, 10);
        path.append (new Line2D.Double (end.x, end.y, tip.x, tip.y), false);
        end = new Vector2 (tip, angle - da, 10);
        path.append (new Line2D.Double (tip.x, tip.y, end.x, end.y), false);
        shape = path;

        bounds = shape.getBounds ();
        int t = (int) Math.ceil (PanelEquationGraph.strokeThickness / 2);
        bounds.grow (t, t);
    }

    public void paintComponent (Graphics g)
    {
        // If this class gets changed into a proper Swing component (such a JPanel),
        // then handle g in the appropriate manner. For now, we simply assume that
        // the caller is using us as subroutine, and that g is a fully configured Graphics2D,
        // including stroke, color and rendering hints.
        Graphics2D g2 = (Graphics2D) g;

        if (shape != null) g2.draw (shape);
        // TODO: draw text of fromName
    }

    /**
        Finds the point on the edge of the given rectangle nearest to the given point.
        Returns null if the given point is inside the rectangle.
    **/
    public static Vector2 intersection (Segment2 s0, Rectangle b)
    {
        double x0 = b.getMinX ();
        double y0 = b.getMinY ();
        double x1 = b.getMaxX ();
        double y1 = b.getMaxY ();

        // left edge
        Vector2 p0 = new Vector2 (x0, y0);
        Vector2 p1 = new Vector2 (x0, y1);
        double t = s0.intersection (new Segment2 (p0, p1));

        // bottom edge
        p0.x = p1.x;
        p0.y = p1.y;
        p1.x = x1;
        t = Math.min (t, s0.intersection (new Segment2 (p0, p1)));

        // right edge
        p0.x = p1.x;
        p0.y = p1.y;
        p1.y = y0;
        t = Math.min (t, s0.intersection (new Segment2 (p0, p1)));

        // top edge
        p0.x = p1.x;
        p0.y = p1.y;
        p1.x = x0;
        t = Math.min (t, s0.intersection (new Segment2 (p0, p1)));

        if (t > 1) return null;
        return s0.paramtetricPoint (t);
    }

    public static class Vector2
    {
        double x;
        double y;

        public Vector2 (double x, double y)
        {
            this.x = x;
            this.y = y;
        }

        public Vector2 (Vector2 origin, double angle, double length)
        {
            x = origin.x + Math.cos (angle) * length;
            y = origin.y + Math.sin (angle) * length;
        }

        public Vector2 add (Vector2 that)
        {
            return new Vector2 (x + that.x, y + that.y);
        }

        public Vector2 subtract (Vector2 that)
        {
            return new Vector2 (x - that.x, y - that.y);
        }

        public Vector2 multiply (double a)
        {
            return new Vector2 (x * a, y * a);
        }

        public double cross (Vector2 that)
        {
            return x * that.y - y * that.x;
        }

        public double dot (Vector2 that)
        {
            return x * that.x + y * that.y;
        }

        public double distance (Vector2 that)
        {
            double dx = x - that.x;
            double dy = y - that.y;
            return Math.sqrt (dx * dx + dy * dy);
        }

        public double length ()
        {
            return Math.sqrt (x * x + y * y);
        }

        public Vector2 normalize ()
        {
            double l = length ();
            return new Vector2 (x / l, y / l);
        }

        public String toString ()
        {
            return "(" + x + "," + y + ")";
        }
    }

    public static class Segment2
    {
        Vector2 a;
        Vector2 b;

        public Segment2 (Vector2 a, Vector2 b)
        {
            this.a = a;
            this.b = b.subtract (a);
        }

        /**
            Finds the point where this segment and that segment intersect.
            Result is a number in [0,1].
            If the segments are parallel or do not intersect, then the result is infinity.

            From https://stackoverflow.com/questions/563198/how-do-you-detect-where-two-line-segments-intersect
            which is in turn based on "Intersection of two lines in three-space" by Ronald Goldman,
            published in Graphics Gems, page 304.

            This is equivalent to a linear solution which involves matrix inversion
            (such as https://blogs.sas.com/content/iml/2018/07/09/intersection-line-segments.html).
            However, this approach offers a clear sequence of early-outs that make it efficient.
        **/
        public double intersection (Segment2 that)
        {
            double rs = b.cross (that.b);
            if (rs == 0) return Double.POSITIVE_INFINITY;  // Singular, so no solution
            Vector2 qp = that.a.subtract (a);
            double t = qp.cross (that.b) / rs;  // Parameter for point on this segment
            if (t < 0  ||  t > 1) return Double.POSITIVE_INFINITY;  // Not between start and end points
            double u = qp.cross (b) / rs;  // Parameter for point on that segment
            if (u < 0  ||  u > 1) return Double.POSITIVE_INFINITY;
            return t;
        }

        public Vector2 paramtetricPoint (double t)
        {
            return a.add (b.multiply (t));
        }

        public double angle ()
        {
            return Math.atan2 (b.y, b.x);
        }

        public String toString ()
        {
            return a + "->" + a.add (b);
        }
    }
}