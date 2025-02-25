/*
 * Copyright 2012-2014, Luis Filipe da Cruz Nassif
 * 
 * This file is part of Indexador e Processador de Evidências Digitais (IPED).
 *
 * IPED is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * IPED is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with IPED.  If not, see <http://www.gnu.org/licenses/>.
 */
package dpf.sp.gpinf.indexer.desktop;

import java.awt.Dialog;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.RowSorter;

import dpf.sp.gpinf.indexer.desktop.parallelsorter.ParallelTableRowSorter;
import iped3.desktop.CancelableWorker;
import iped3.desktop.ProgressDialog;

public class ResultTableRowSorter extends ParallelTableRowSorter<ResultTableSortModel> {
    
    private static final int MAX_COMPARATOR_CACHE = 3;

    private static volatile Map<Integer, RowComparator> comparatorCache = new LinkedHashMap<Integer, RowComparator>(16, 0.75f, true){
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, RowComparator> eldest) {
            return this.size() > MAX_COMPARATOR_CACHE;
        }
    };

    public ResultTableRowSorter() {
        super(new ResultTableSortModel());
        this.setSortable(0, false);
        this.setMaxSortKeys(2);
    }

    @Override
    public Comparator<?> getComparator(int column) {
        if (RowComparator.isNewIndexReader())
            comparatorCache.clear();
        RowComparator comp = comparatorCache.get(column);
        if (comp == null) {
            comp = new RowComparator(column);
            comparatorCache.put(column, comp);
        }
        return comp;
    }

    @Override
    protected boolean useToString(int column) {
        return false;
    }

    @Override
    public void setSortKeys(final List<? extends SortKey> sortKeys) {
        if (sortKeys == null) {
            super.setSortKeys(null);
            App.get().resultsModel.fireTableDataChanged();
        } else {
            BackgroundSort backgroundSort = new BackgroundSort(sortKeys);
            backgroundSort.execute();
        }
    }

    public void setSortKeysSuper(final List<? extends SortKey> sortKeys) {
        super.setSortKeys(sortKeys);
    }

    class BackgroundSort extends CancelableWorker {

        ProgressDialog progressMonitor;
        List<? extends SortKey> sortKeys;
        ResultTableRowSorter sorter = new ResultTableRowSorter();

        public BackgroundSort(List<? extends SortKey> sortKeys) {
            this.sortKeys = sortKeys;
            progressMonitor = new ProgressDialog(App.get(), this, true, 200, Dialog.ModalityType.APPLICATION_MODAL);
            progressMonitor.setNote(Messages.getString("ResultTableRowSorter.Sorting")); //$NON-NLS-1$
        }

        @Override
        protected Object doInBackground() {

            sorter.setSortKeysSuper(sortKeys);

            return null;
        }

        @Override
        public void done() {
            progressMonitor.close();
            int idx = App.get().resultsTable.getSelectionModel().getLeadSelectionIndex();
            if (idx != -1) {
                idx = App.get().resultsTable.convertRowIndexToModel(idx);
            }

            RowSorter oldSorter = App.get().resultsTable.getRowSorter();
            App.get().resultsTable.setRowSorter(null);

            if (!this.isCancelled()) {
                App.get().resultsTable.setRowSorter(sorter);
            } else {
                App.get().resultsTable.setRowSorter(oldSorter);
            }

            App.get().resultsModel.fireTableDataChanged();
            App.get().resultsTable.getTableHeader().repaint();
            App.get().galleryModel.fireTableStructureChanged();

            if (idx != -1) {
                idx = App.get().resultsTable.convertRowIndexToView(idx);
                App.get().resultsTable.setRowSelectionInterval(idx, idx);
            }

        }

        @Override
        public boolean doCancel(boolean mayInterruptIfRunning) {
            cancel(true);
            return true;
        }

    }

}
