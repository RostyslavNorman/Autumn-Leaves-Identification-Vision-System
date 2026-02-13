package model;

public class DisjointSetNode<T> {
    public DisjointSetNode<?> parent = null;
    public T data;
    public DisjointSetNode(T data) {
        this.data = data;
    }

    public DisjointSetNode<?> getParent() {
        return parent;
    }

    public void setParent(DisjointSetNode<?> parent) {
        this.parent = parent;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public static DisjointSetNode<?> find(DisjointSetNode<?> node) {
        if(node.parent == null) return node;
        else return find(node.parent);
    }
}
