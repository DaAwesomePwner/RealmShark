package tomato.gui.history;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.time.Instant;
import java.util.List;
import tomato.gui.modern.ContentStyle;
import tomato.gui.modern.DisplayFormat;

public final class HistoryTables {
    private HistoryTables() { }
    public static JTable table(String name, String[] columns, Class<?>[] types, List<Object[]> rows) {
        DefaultTableModel model = new DefaultTableModel(columns, 0) {
            public boolean isCellEditable(int r,int c){return false;}
            public Class<?> getColumnClass(int c){return types[c];}
        };
        for(Object[] row:rows)model.addRow(row);
        JTable table=new JTable(model);table.setName(name);ContentStyle.table(table);table.setAutoCreateRowSorter(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setDefaultRenderer(Double.class,new ContentStyle.Cell(){protected void setValue(Object value){setText(value==null?DisplayFormat.UNAVAILABLE:DisplayFormat.formatNumber(((Number)value).doubleValue(),0,2));}});
        table.setDefaultRenderer(Long.class,new ContentStyle.Cell(){protected void setValue(Object value){setText(DisplayFormat.formatInteger((Long)value));}});
        table.setDefaultRenderer(Instant.class,new ContentStyle.Cell(){protected void setValue(Object value){setText(DisplayFormat.formatTimestamp((Instant)value));}});
        for(int i=0;i<columns.length;i++)table.getColumnModel().getColumn(i).setPreferredWidth(i==0?240:145);
        return table;
    }
    public static JComponent page(JTable table,String note){
        JPanel panel=new JPanel(new BorderLayout(0,6));panel.add(ContentStyle.tableScroll(table,3));
        panel.add(ContentStyle.wrappingText(note),BorderLayout.SOUTH);return panel;
    }
}
