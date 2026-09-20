package tomato.gui.activity;

import packets.packetcapture.logger.ActivityJournal;
import packets.data.enums.ConditionBits;
import packets.data.enums.ConditionNewBits;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.DisplayFormat;

/** Time-aligned local resource samples and observed condition intervals. Gaps stay blank. */
public final class CombatTimelineChart extends JPanel implements Scrollable {
    private static final int LEFT=156, TOP=36, GRAPH=80, ROW=24;
    private ActivityJournal.Visit visit;
    private String filter="";
    private int zoom=1;
    private int inspected=-1;
    private String inspectionSummary="No resource sample selected. Focus the chart and use Left / Right to inspect samples; + / − to zoom; 0 to reset.";
    private Locale inspectionLocale;
    private final List<Lane> lanes=new ArrayList<>();
    public CombatTimelineChart(){
        setName("combat-timeline-chart");setToolTipText("");setOpaque(true);setFocusable(true);setFont(ContentStyle.body());
        getAccessibleContext().setAccessibleName("Local resource and condition timeline");
        getAccessibleContext().setAccessibleDescription(inspectionSummary);
        bind("zoom-in","Zoom in",()->setZoom(zoom+1),"PLUS","EQUALS","shift EQUALS","ADD");
        bind("zoom-out","Zoom out",()->setZoom(zoom-1),"MINUS","SUBTRACT");
        bind("reset-zoom","Reset zoom",()->setZoom(1),"0","NUMPAD0","ctrl 0");
        bind("previous-sample","Previous sample",()->inspect(inspected<0?sampleCount()-1:inspected-1),"LEFT");
        bind("next-sample","Next sample",()->inspect(inspected+1),"RIGHT");
        bind("first-sample","First sample",()->inspect(0),"HOME");
        bind("last-sample","Last sample",()->inspect(sampleCount()-1),"END");
        addFocusListener(new FocusAdapter(){public void focusGained(FocusEvent e){repaint();}public void focusLost(FocusEvent e){repaint();}});
        addMouseListener(new MouseAdapter(){@Override public void mousePressed(MouseEvent e){
            requestFocusInWindow();
            if(visit!=null&&e.getX()>=left()&&e.getX()<=getWidth()-20&&e.getY()>=TOP&&e.getY()<=TOP+GRAPH)
                inspect(nearestSample(timeAt(e.getX())));
        }});
        addMouseWheelListener(e->{if(e.isControlDown()){setZoom(zoom-e.getWheelRotation());e.consume();}});
    }
    @Override public void updateUI(){super.updateUI();setBackground(ContentStyle.color("background"));setForeground(ContentStyle.color("text"));}
    private void bind(String key,String name,Runnable action,String... strokes){
        getActionMap().put(key,new AbstractAction(name){public void actionPerformed(ActionEvent e){action.run();}});
        for(String stroke:strokes)getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(stroke),key);
    }
    private void setZoom(int value){int next=Math.max(1,Math.min(12,value));if(next==zoom)return;zoom=next;revalidate();repaint();}
    public String getInspectionSummary(){return inspectionSummary;}
    void refreshPresentation(){
        if(!Locale.getDefault(Locale.Category.FORMAT).equals(inspectionLocale)){updateInspection();repaint();}
    }
    private int sampleCount(){return visit==null?0:visit.resourceTimeline.size();}
    private void inspect(int index){
        inspected=sampleCount()==0?-1:Math.max(0,Math.min(sampleCount()-1,index));updateInspection();repaint();
        if(inspected>=0)scrollRectToVisible(new Rectangle(x(visit.resourceTimeline.get(inspected).time)-12,TOP,24,GRAPH));
    }
    private void updateInspection(){
        String text;
        if(inspected<0)text=sampleCount()==0?"No recorded resource samples. Aggregate uptime may still be available."
            :DisplayFormat.formatInteger(sampleCount())+" resource samples · Focus chart: Left / Right inspect, Home / End first / last; + / − or Ctrl+wheel zoom, 0 reset.";
        else{
            ActivityJournal.ResourcePoint p=visit.resourceTimeline.get(inspected);
            text="Sample "+DisplayFormat.formatInteger(inspected+1)+" of "+DisplayFormat.formatInteger(sampleCount())
                +" · "+DisplayFormat.formatDurationSeconds(p.time-visit.started,1)+"s · HP "+DisplayFormat.formatExact(p.hp)+" · MP "+DisplayFormat.formatExact(p.mp);
            ActivityJournal.ConditionSlice observed=null;
            for(ActivityJournal.ConditionSlice slice:visit.conditionTimeline)if(p.time>=slice.start&&p.time<slice.end){observed=slice;break;}
            text+=" · Conditions: "+conditionSummary(observed);
        }
        String previous=inspectionSummary;inspectionSummary=text;inspectionLocale=Locale.getDefault(Locale.Category.FORMAT);getAccessibleContext().setAccessibleDescription(text);
        firePropertyChange("inspectionSummary",previous,text);
    }
    private static String conditionSummary(ActivityJournal.ConditionSlice slice){
        if(slice==null)return "unknown coverage";
        List<String> active=new ArrayList<>();
        if(slice.primary==null)active.add("primary flags unknown");
        else{boolean found=false;for(ConditionBits bit:ConditionBits.values())if((slice.primary&bit.value())!=0){active.add(label(bit.name()));found=true;}if(!found)active.add("no active primary flags");}
        if(slice.secondary==null)active.add("extra flags unknown");
        else{boolean found=false;for(ConditionNewBits bit:ConditionNewBits.values())if((slice.secondary&bit.value())!=0){active.add(label(bit.name()));found=true;}if(!found)active.add("no active extra flags");}
        return String.join(", ",active);
    }
    public void setVisit(ActivityJournal.Visit visit){
        boolean changed=this.visit==null||visit==null||!Objects.equals(this.visit.id,visit.id);
        long selectedTime=inspected<0||this.visit==null?-1:this.visit.resourceTimeline.get(inspected).time;
        boolean same=!changed&&samePlot(this.visit,visit);
        this.visit=visit;
        if(changed){zoom=1;inspected=-1;}
        else if(inspected>=0){inspected=nearestSample(selectedTime);if(inspected>=0&&visit.resourceTimeline.get(inspected).time!=selectedTime)inspected=-1;}
        if(same){refreshPresentation();return;}
        rebuild();updateInspection();
    }
    private static boolean samePlot(ActivityJournal.Visit a,ActivityJournal.Visit b){
        if(a.started!=b.started||a.lastSeen!=b.lastSeen||a.extraConditionObservedMillis!=b.extraConditionObservedMillis
            ||!a.conditions.keySet().equals(b.conditions.keySet())||!a.extraConditions.keySet().equals(b.extraConditions.keySet())
            ||a.resourceTimeline.size()!=b.resourceTimeline.size()||a.conditionTimeline.size()!=b.conditionTimeline.size())return false;
        for(int i=0;i<a.resourceTimeline.size();i++){ActivityJournal.ResourcePoint p=a.resourceTimeline.get(i),q=b.resourceTimeline.get(i);
            if(p.time!=q.time||!Objects.equals(p.hp,q.hp)||!Objects.equals(p.mp,q.mp))return false;}
        for(int i=0;i<a.conditionTimeline.size();i++){ActivityJournal.ConditionSlice p=a.conditionTimeline.get(i),q=b.conditionTimeline.get(i);
            if(p.start!=q.start||p.end!=q.end||!Objects.equals(p.primary,q.primary)||!Objects.equals(p.secondary,q.secondary))return false;}
        return true;
    }
    public void setFilter(String value){String next=value.toLowerCase(Locale.ROOT);if(next.equals(filter))return;filter=next;rebuild();}
    private void rebuild(){
        lanes.clear();
        if(visit!=null){
            lanes.add(new Lane("Observed conditions",0,false));
            for(ConditionBits bit:ConditionBits.values())if(visit.conditions.containsKey(bit.name())&&bit.name().toLowerCase(Locale.ROOT).contains(filter))lanes.add(new Lane(bit.name(),bit.value(),false));
            if(visit.extraConditionObservedMillis>0){
                lanes.add(new Lane("Observed extra flags",0,true));
                for(ConditionNewBits bit:ConditionNewBits.values())if(visit.extraConditions.containsKey(bit.name())&&bit.name().toLowerCase(Locale.ROOT).contains(filter))lanes.add(new Lane(bit.name(),bit.value(),true));
            }
        }
        revalidate();repaint();
    }
    @Override public Dimension getPreferredSize(){int base=getParent()==null?760:Math.max(320,getParent().getWidth());return new Dimension(base*zoom,Math.max(300,TOP+GRAPH+70+lanes.size()*rowHeight()));}
    private int left(){return Math.max(100,Math.min(Math.max(LEFT,getFontMetrics(ContentStyle.metadata(getFont())).stringWidth("Observed conditions")+18),Math.max(100,getWidth()/2)));}
    private int rowHeight(){return Math.max(ROW,getFontMetrics(ContentStyle.metadata(getFont())).getHeight()+6);}
    private long span(){return visit==null?1:Math.max(1,visit.lastSeen-visit.started);}
    private int x(long time){return left()+(int)((time-visit.started)*(double)Math.max(1,getWidth()-left()-20)/span());}
    private long timeAt(int x){return visit.started+(long)((x-left())*(double)span()/Math.max(1,getWidth()-left()-20));}
    private int nearestSample(long time){int nearest=-1;long distance=Long.MAX_VALUE;for(int i=0;i<sampleCount();i++){long d=Math.abs(visit.resourceTimeline.get(i).time-time);if(d<distance){distance=d;nearest=i;}}return nearest;}
    @Override protected void paintComponent(Graphics graphics){
        super.paintComponent(graphics);Graphics2D g=(Graphics2D)graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        g.setFont(ContentStyle.metadata(getFont()));g.setColor(ContentStyle.color("text"));
        if(hasFocus()){g.setColor(ContentStyle.color("violet"));Rectangle visible=getVisibleRect();g.drawRect(visible.x+1,visible.y+1,Math.max(0,visible.width-3),Math.max(0,visible.height-3));g.setColor(ContentStyle.color("text"));}
        if(visit==null){g.drawString("No recorded visit selected. Start capture and enter an area.",18,35);g.dispose();return;}
        g.drawString("Local HP / MP",18,24);
        int left=left(),width=Math.max(1,getWidth()-left-20);
        g.setColor(ContentStyle.color("surface"));g.fillRoundRect(left,TOP,width,GRAPH,4,4);
        int maximum=1;for(ActivityJournal.ResourcePoint p:visit.resourceTimeline){if(p.hp!=null)maximum=Math.max(maximum,p.hp);if(p.mp!=null)maximum=Math.max(maximum,p.mp);}
        g.setColor(getForeground());g.drawString("HP",18,TOP+22);g.setColor(ContentStyle.color("mint"));g.fillRect(55,TOP+13,20,5);
        g.setColor(getForeground());g.drawString("MP",18,TOP+44);g.setColor(ContentStyle.color("blue"));g.fillRect(55,TOP+35,20,5);
        g.setColor(getForeground());g.drawString(DisplayFormat.formatInteger(0)+" – "+DisplayFormat.formatInteger(maximum),18,TOP+68);
        drawResources(g,maximum,true);drawResources(g,maximum,false);
        if(visit.resourceTimeline.isEmpty()){g.setColor(getForeground());g.drawString("No time samples in this recording",left+10,TOP+25);}
        if(inspected>=0){g.setColor(ContentStyle.color("amber"));int px=x(visit.resourceTimeline.get(inspected).time);g.drawLine(px,TOP,px,TOP+GRAPH);}
        g.setColor(getForeground());
        int ticks=Math.max(1,Math.min(4,width/Math.max(60,g.getFontMetrics().stringWidth("000.0s")+12)));
        for(int i=0;i<=ticks;i++){int px=left+width*i/ticks;String t=DisplayFormat.formatNumber(span()*i/(1000.0*ticks),1)+"s";g.drawString(t,Math.min(px,getWidth()-g.getFontMetrics().stringWidth(t)-8),TOP+GRAPH+20);}
        int y=TOP+GRAPH+48;
        for(Lane lane:lanes){
            g.setColor(getForeground());Shape clip=g.getClip();g.clipRect(0,y,left-6,rowHeight());g.drawString(label(lane.name),8,y+g.getFontMetrics().getAscent()+2);g.setClip(clip);
            g.setColor(ContentStyle.color("surface"));g.fillRect(left,y,width,rowHeight()-6);
            for(ActivityJournal.ConditionSlice slice:visit.conditionTimeline){
                Integer mask=lane.extra?slice.secondary:slice.primary;
                if(mask==null||lane.mask!=0&&(mask&lane.mask)==0)continue;
                g.setColor(ContentStyle.color(lane.mask==0?"muted":"violet"));
                int from=Math.max(left,x(slice.start)),to=Math.min(getWidth()-20,x(slice.end));
                if(to>=from)g.fillRect(from,y,Math.max(1,to-from),rowHeight()-6);
            }
            y+=rowHeight();
        }
        if(visit.conditionTimeline.isEmpty()){g.setColor(getForeground());g.drawString("No buff time samples; aggregate uptime may still be available.",18,y+18);}
        g.dispose();
    }
    private void drawResources(Graphics2D g,int maximum,boolean hp){
        g.setColor(ContentStyle.color(hp?"mint":"blue"));g.setStroke(new BasicStroke(2));ActivityJournal.ResourcePoint previous=null;
        for(ActivityJournal.ResourcePoint p:visit.resourceTimeline){
            Integer value=hp?p.hp:p.mp;if(value==null){previous=null;continue;}
            int px=x(p.time),py=TOP+GRAPH-(int)(Math.max(0,value)*(double)GRAPH/maximum);
            if(previous!=null&&p.time>=previous.time&&p.time-previous.time<=2000){Integer before=hp?previous.hp:previous.mp;
                if(before!=null){int bx=x(previous.time),by=TOP+GRAPH-(int)(Math.max(0,before)*(double)GRAPH/maximum);g.drawLine(bx,by,px,by);g.drawLine(px,by,px,py);}}
            g.fillOval(px-2,py-2,4,4);previous=p;
        }
    }
    @Override public String getToolTipText(MouseEvent event){
        if(visit==null||event.getX()<left()||event.getX()>getWidth()-20)return null;
        long time=timeAt(event.getX());
        String at=DisplayFormat.formatDurationSeconds(time-visit.started,1)+"s";
        int lane=(event.getY()-(TOP+GRAPH+48))/rowHeight();
        if(event.getY()>=TOP+GRAPH+48&&lane>=0&&lane<lanes.size()){
            Lane row=lanes.get(lane);
            for(ActivityJournal.ConditionSlice slice:visit.conditionTimeline)if(time>=slice.start&&time<slice.end){Integer mask=row.extra?slice.secondary:slice.primary;
                return at+" · "+label(row.name)+" · "+(mask==null?"Unknown":row.mask==0?"Observed":(mask&row.mask)!=0?"Active":"Inactive");}
            return at+" · Unknown coverage";
        }
        ActivityJournal.ResourcePoint nearest=null;long distance=Long.MAX_VALUE;
        for(ActivityJournal.ResourcePoint p:visit.resourceTimeline){long d=Math.abs(p.time-time);if(d<distance){distance=d;nearest=p;}}
        return nearest==null||distance>1000?at+" · No nearby resource sample":at+" · HP "+DisplayFormat.formatExact(nearest.hp)+" · MP "+DisplayFormat.formatExact(nearest.mp)+" (nearest sample)";
    }
    private static String label(String text){return text.replace('_',' ');}
    public Dimension getPreferredScrollableViewportSize(){return new Dimension(700,320);}
    public int getScrollableUnitIncrement(Rectangle r,int o,int d){return rowHeight();}
    public int getScrollableBlockIncrement(Rectangle r,int o,int d){return Math.max(rowHeight(),(o==SwingConstants.VERTICAL?r.height:r.width)-rowHeight());}
    public boolean getScrollableTracksViewportWidth(){return zoom==1;}
    public boolean getScrollableTracksViewportHeight(){return false;}
    private static final class Lane{final String name;final int mask;final boolean extra;Lane(String n,int m,boolean e){name=n;mask=m;extra=e;}}
}
