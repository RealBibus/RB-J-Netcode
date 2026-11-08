package netcore.predict;







public interface SimModel<S> {

    S copy(S state);

    
    S step(S state, InputCommand input);

    
    double error(S authoritative, S predicted);

    
    S lerp(S from, S to, double t);
}
